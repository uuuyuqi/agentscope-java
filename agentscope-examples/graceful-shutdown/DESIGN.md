# AgentScope 优雅下线设计文档

## 一、问题背景

### 1.1 业务场景

在 Kubernetes 等云原生环境中进行滚动发布时，Pod 收到 SIGTERM 信号后需要优雅退出。对于 AI Agent 应用，存在以下挑战：

- Agent 执行时间长（可能数十秒甚至数分钟）
- 执行过程包含多轮 reasoning + acting
- 中途中断会导致用户体验差、资源浪费

### 1.2 目标

1. 收到关闭信号后，停止接收新请求
2. 等待正在执行的请求完成（有超时限制）
3. 超时后中断请求，但保存执行状态
4. 用户重试时能从断点恢复，而非重新开始

---

## 二、设计演进

### 2.1 初始方案（问题分析）

最初的设计在业务层检查 shutdown 状态：

```java
// 错误的设计 ❌
@Service
public class OrderService {
    public Flux<OrderResponse> processOrder(OrderRequest request) {
        // 业务层检查 shutdown 状态
        if (!shutdownManager.isAcceptingRequests()) {
            return Flux.just(OrderResponse.rejected("Service is shutting down"));
        }
        
        // 业务层手动注册
        shutdownManager.registerRequest(sessionId, agent, session);
        
        // ... 执行逻辑
        
        // 业务层手动注销
        shutdownManager.unregisterRequest(sessionId);
        
        // 业务层删除 session
        session.delete(sessionKey);
    }
}
```

**问题分析**：

| 问题 | 说明 |
|------|------|
| 职责混乱 | 业务层不应该知道 shutdown 机制的存在 |
| 代码侵入 | 每个业务方法都需要添加 shutdown 检查逻辑 |
| Session 误解 | Session 是用户会话，不应该在请求结束后删除 |
| 手动注册 | 开发者容易忘记注册/注销，导致状态不一致 |

### 2.2 核心问题定位

经过分析，识别出三个核心问题：

1. **谁来检查 shutdown？** —— 应该是 Agent 自己（通过 Hook），而非业务层
2. **谁来保存状态？** —— 应该是框架自动处理，而非业务层手动调用
3. **Session 是什么？** —— 是用户会话（跨请求），不是请求会话（单次请求）

采用 Hook 机制实现框架层自动处理：

```java
// 正确的设计 ✓
@Service
public class OrderService {
    public Flux<OrderResponse> processOrder(OrderRequest request) {
        // 1. 确定 sessionId
        String sessionId = request.sessionId() != null 
            ? request.sessionId() 
            : generateSessionId();
        
        // 2. 创建 Agent + Hook（自动注册、自动检测、自动保存、自动恢复）
        GracefulShutdownHook hook = new GracefulShutdownHook(session, sessionKey);
        ReActAgent agent = ReActAgent.builder()
            .hooks(List.of(hook))
            .build();
        
        // 3. 加载状态（Hook 会自动检测 InterruptedState 并注入 resume 消息）
        agent.loadIfExists(session, sessionKey);
        
        // 4. 正常发送消息执行（框架自动处理 shutdown 和 resume）
        // 业务层完全不需要区分新请求还是恢复请求
        return agent.stream(buildUserMessage(request))
            .doOnComplete(() -> hook.complete())
            .onErrorResume(AgentAbortedException.class, e -> ...);
    }
}
```

---

## 三、核心设计原则

### 3.1 关注点分离

| 层次 | 职责 | 不做什么 |
|------|------|----------|
| 业务层 | 业务逻辑、构建消息、处理响应 | 不检查 shutdown、不手动注册/注销 |
| 框架层 | shutdown 检测、状态保存、注册管理 | 不理解业务逻辑 |
| Session 层 | 状态持久化、跨请求复用 | 不管理请求生命周期 |

### 3.2 Session 生命周期

```
┌─────────────────────────────────────────────────────────────┐
│                    用户 Session 生命周期                      │
│                                                              │
│  请求1        请求2        请求3 (中断)    请求4 (恢复)         │
│    │            │             │              │               │
│    ▼            ▼             ▼              ▼               │
│ ┌──────┐   ┌──────┐    ┌──────────┐    ┌──────────┐         │
│ │ 执行  │   │ 执行  │    │ 执行+中断 │    │ 恢复执行  │         │
│ │ 完成  │   │ 完成  │    │ 保存状态 │    │ 完成     │         │
│ └──────┘   └──────┘    └──────────┘    └──────────┘         │
│                             │               │                │
│                        InterruptedState  清除标记            │
│                        被保存到 Session   保留 Session        │
│                                                              │
│  ════════════════════ Session 永不删除 ════════════════════  │
└─────────────────────────────────────────────────────────────┘
```

**关键点**：
- Session 是用户级别的，跨多次请求存在
- 中断时保存 `InterruptedState` 标记，不删除 Session
- 恢复完成后清除标记，仍然保留 Session

### 3.3 Hook 机制

Hook 在 Agent 执行的关键点（reasoning 前、acting 前）被调用，实现非侵入式的 shutdown 检测：

```
Agent 执行流程：
                                        
    ┌───────────────────────────────────────┐
    │           Agent.stream()              │
    │                                       │
    │   ┌─────────────┐                     │
    │   │ reasoning() │◄── PreReasoningEvent│
    │   └──────┬──────┘         │           │
    │          │                │           │
    │          ▼          Hook.onEvent()    │
    │   ┌─────────────┐    检查 shutdown    │
    │   │  acting()   │◄── PreActingEvent   │
    │   └──────┬──────┘         │           │
    │          │                │           │
    │          ▼          检测到 shutdown:  │
    │   ┌─────────────┐    1. 保存 InterruptedState
    │   │    ...      │    2. event.abort() │
    │   └─────────────┘    3. 抛出异常      │
    │                                       │
    └───────────────────────────────────────┘
```

---

## 四、组件设计

### 4.1 GracefulShutdownHook

核心职责：
1. **自动注册**：首次 PreReasoningEvent 时自动注册
2. **自动恢复**：首次 PreReasoningEvent 时检测 InterruptedState，自动注入 SYSTEM 消息提示 LLM 继续
3. **状态检测**：每次事件时检查 isAcceptingRequests()
4. **状态保存**：检测到 shutdown 时保存 Agent 状态 + InterruptedState
5. **完成清理**：complete() 时清除 InterruptedState、保存最终状态、注销

```java
public class GracefulShutdownHook implements Hook {
    
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 自动注册
        if (!registered && event instanceof PreReasoningEvent) {
            ensureRegistered(event.getAgent());
        }
        
        // 检测 shutdown
        if (!shutdownManager.isAcceptingRequests()) {
            handleShutdown(event);  // 保存状态 + abort
        }
        
        return Mono.just(event);
    }
    
    public void complete() {
        session.delete(sessionKey, InterruptedState.KEY);  // 清除标记
        registeredAgent.saveTo(session, sessionKey);       // 保存最终状态
        shutdownManager.unregisterRequest(sessionKey);     // 注销
    }
}
```

### 4.2 InterruptedState

简单的状态标记，用于判断是否需要恢复：

```java
public record InterruptedState(
    String reason,           // 中断原因
    Instant interruptedAt    // 中断时间
) implements State {
    
    public static final String KEY = "interrupted_state";
    
    public static InterruptedState now(String reason) {
        return new InterruptedState(reason, Instant.now());
    }
}
```

### 4.3 PreReasoningEvent / PreActingEvent 增强

支持 abort 时指定状态保存位置：

```java
public class PreReasoningEvent extends HookEvent {
    
    // 简单 abort（不保存状态）
    public void abort(String reason) {
        this.aborted = true;
        this.abortReason = reason;
    }
    
    // abort 并保存状态
    public void abort(String reason, Session session, SessionKey sessionKey) {
        this.aborted = true;
        this.abortReason = reason;
        this.abortSaveSession = session;
        this.abortSaveSessionKey = sessionKey;
    }
}
```

ReActAgent 在处理 abort 时自动保存状态：

```java
// ReActAgent.reasoning() 中
private Mono<PreReasoningEvent> handlePreReasoningAbort(PreReasoningEvent event) {
    if (event.isAborted()) {
        if (event.isSaveStateOnAbort()) {
            // 自动保存 Agent 状态
            saveTo(event.getAbortSaveSession(), event.getAbortSaveSessionKey());
        }
        return Mono.error(new AgentAbortedException(
            event.getAbortReason(), 
            event.getAbortSaveSessionKey(),
            event.isSaveStateOnAbort()
        ));
    }
    return Mono.just(event);
}
```

---

## 五、关键设计决策

### 5.1 为什么用 Hook 而非 AOP/拦截器？

| 方案 | 优点 | 缺点 |
|------|------|------|
| AOP | 对业务完全透明 | 无法获取 Agent 内部状态 |
| 拦截器 | 配置简单 | 粒度太粗，只能拦截整个请求 |
| **Hook** | 在 Agent 内部关键点触发，可访问状态 | 需要 Agent 框架支持 |

Hook 方案的优势：
- 在 reasoning/acting **之前**检测，可以避免开始无法完成的操作
- 可以访问 Agent 内部状态，实现精准的状态保存
- 与 Agent 执行流程紧密集成，不破坏响应式流

### 5.2 为什么 Session 不删除？

考虑以下场景：
1. 用户开始对话，生成 session-123
2. 对话进行到一半，服务下线
3. 服务重启，用户带着 session-123 恢复
4. 对话继续完成
5. **用户可能还想继续这个对话**（比如追问、修改）

如果每次完成就删除 Session，用户就无法：
- 查看历史对话
- 继续追问
- 基于之前的上下文进行新的请求

### 5.3 为什么用 InterruptedState 而非直接检查 Agent 状态？

Agent 状态始终存在（即使正常完成），无法区分：
- 正常完成后保存的状态
- 中断后保存的状态

`InterruptedState` 是一个**显式标记**，明确表示：
- 之前的执行被中断了
- 需要恢复执行，而非重新开始

### 5.4 为什么统一 API 而非分开 /process 和 /resume？

分开 API 的问题：
- 客户端需要知道是新请求还是恢复请求
- 客户端逻辑变复杂
- 如果客户端用错 API（新请求用 /resume），服务端要额外处理

统一 API 的优势：
- 客户端逻辑简单：带 sessionId 就是恢复，不带就是新建
- 服务端自动判断，更智能
- 更符合 RESTful 设计（POST /orders/process 就是"处理订单"，无论新旧）

---

## 六、使用指南

### 6.1 最小接入示例

```java
@Service
public class MyService {
    
    private final Model model;
    private final Session session;
    
    public Flux<Response> process(Request request) {
        // 1. 准备 sessionId 和 sessionKey
        String sessionId = request.sessionId() != null 
            ? request.sessionId() 
            : UUID.randomUUID().toString();
        SessionKey sessionKey = SimpleSessionKey.of(sessionId);
        
        // 2. 创建 Hook（自动处理注册、shutdown检测、状态保存、resume）
        GracefulShutdownHook hook = new GracefulShutdownHook(session, sessionKey);
        
        // 3. 创建 Agent
        ReActAgent agent = ReActAgent.builder()
            .model(model)
            .hooks(List.of(hook))
            .build();
        
        // 4. 加载之前的状态（Hook 会自动检测并注入 resume 消息）
        agent.loadIfExists(session, sessionKey);
        
        // 5. 正常构建并发送消息（无需区分新请求还是恢复请求）
        Msg inputMsg = createUserMsg(request);
        
        // 6. 执行
        return agent.stream(inputMsg)
            .doOnComplete(() -> hook.complete())
            .onErrorResume(AgentAbortedException.class, e -> 
                Flux.just(Response.interrupted(sessionId, e.getReason())));
    }
}
```

### 6.2 客户端处理

```javascript
// 发起请求
const response = await fetch('/api/process', {
    method: 'POST',
    body: JSON.stringify({ sessionId: savedSessionId, ... })
});

// 处理 SSE 流
const reader = response.body.getReader();
while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    
    const event = JSON.parse(value);
    
    if (event.status === 'interrupted') {
        // 保存 sessionId 供后续恢复
        localStorage.setItem('sessionId', event.sessionId);
        showMessage('处理中断，请稍后重试');
    } else if (event.status === 'completed') {
        // 清除保存的 sessionId（可选）
        localStorage.removeItem('sessionId');
    }
}
```

---

## 七、总结

本设计通过以下机制实现了 AI Agent 的优雅下线：

| 机制 | 作用 |
|------|------|
| GracefulShutdownHook | 自动注册、检测、保存、清理 |
| InterruptedState | 显式标记中断状态 |
| Session 持久化 | 跨请求保存 Agent 状态 |
| 统一 API | 简化客户端逻辑 |

核心思想：**框架层处理复杂性，业务层保持简洁**。
