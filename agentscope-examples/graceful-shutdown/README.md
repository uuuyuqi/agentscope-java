# AgentScope Graceful Shutdown Demo

本示例演示了 AI Agent 应用在滚动发布时如何实现优雅下线，确保正在处理的请求不会丢失。

## 核心特性

- **自动状态保存**：框架层通过 Hook 自动检测 shutdown，自动保存 Agent 状态
- **断点恢复**：用户携带 sessionId 重试时，自动从保存的状态继续执行
- **统一 API**：单一端点同时支持新建和恢复，客户端逻辑简单
- **Session 持久化**：用户会话跨请求保留，支持后续交互

## 设计原则

- **业务层无感知**：业务代码不需要检查 shutdown 状态
- **框架层自动处理**：通过 `GracefulShutdownHook` 自动注册、检测、保存
- **Session 永不删除**：用户会话持久化，中断只保存标记

> 详细设计文档请参考 [DESIGN.md](./DESIGN.md)

## 业务场景

模拟电商订单处理流程，包含四个步骤：
1. 验证订单 (2秒)
2. 检查库存 (2秒)
3. 处理支付 (3秒)
4. 发送通知 (1秒)

## 快速开始

### 1. 启动 MySQL

```bash
cd agentscope-examples/graceful-shutdown
docker-compose up -d
```

### 2. 设置 API Key

```bash
export DASHSCOPE_API_KEY=your-api-key-here
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

### 4. 提交新订单

```bash
curl -X POST http://localhost:8080/api/orders/process \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "ORDER-001",
    "products": [
      {"id": "PROD-A", "quantity": 2},
      {"id": "PROD-B", "quantity": 1}
    ]
  }'
```

响应中会包含 `sessionId`，客户端应保存以备恢复使用。

### 5. 模拟下线（在处理过程中）

```bash
# 在另一个终端发送关闭信号
kill -TERM $(pgrep -f GracefulShutdownApplication)
```

原请求会收到中断响应，包含用于恢复的 sessionId。

### 6. 重启并恢复

```bash
# 重启应用
mvn spring-boot:run

# 使用保存的 sessionId 恢复（在请求中包含 sessionId）
curl -X POST http://localhost:8080/api/orders/process \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "order-xxxxxxxx",
    "orderId": "ORDER-001",
    "products": [{"id": "PROD-A", "quantity": 2}]
  }'
```

## API 接口

| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/orders/process` | POST | 处理订单（新建或恢复，自动判断） |
| `/api/orders/{sessionId}` | GET | 查询订单会话状态 |
| `/health` | GET | 健康检查（存活探针） |
| `/health/ready` | GET | 就绪检查（下线时返回 503） |

### 请求格式

```json
{
  "sessionId": "order-abc12345",  // 可选，不传则新建
  "orderId": "ORDER-001",
  "products": [
    {"id": "PROD-A", "quantity": 2}
  ]
}
```

### 响应格式（SSE 流）

```json
// 处理中
{"sessionId": "order-abc12345", "status": "processing", "step": "reasoning", ...}

// 恢复执行
{"sessionId": "order-abc12345", "status": "resumed", "message": "Resuming from saved state"}

// 被中断
{"sessionId": "order-abc12345", "status": "interrupted", "message": "Service is shutting down..."}

// 完成
{"sessionId": "order-abc12345", "status": "completed", "message": "Order processed"}
```

## 核心代码示例

```java
@Service
public class OrderService {
    
    public Flux<OrderResponse> processOrder(OrderRequest request) {
        // 1. 确定 sessionId
        String sessionId = request.sessionId() != null 
            ? request.sessionId() 
            : "order-" + UUID.randomUUID().toString().substring(0, 8);
        SessionKey sessionKey = SimpleSessionKey.of(sessionId);
        
        // 2. 创建 Hook（自动注册、检测、保存）
        GracefulShutdownHook hook = new GracefulShutdownHook(session, sessionKey);
        
        // 3. 创建 Agent
        ReActAgent agent = ReActAgent.builder()
            .hooks(List.of(hook))
            .build();
        
        // 4. 加载之前的状态
        agent.loadIfExists(session, sessionKey);
        
        // 5. 检查是否需要恢复
        boolean isResume = session.get(sessionKey, InterruptedState.KEY, InterruptedState.class).isPresent();
        Msg inputMsg = isResume ? createResumeMsg() : createNewOrderMsg(request);
        
        // 6. 执行
        return agent.stream(inputMsg)
            .doOnComplete(() -> hook.complete())  // 清除中断标记
            .onErrorResume(AgentAbortedException.class, e -> 
                Flux.just(OrderResponse.interrupted(sessionId, e.getReason())));
    }
}
```

## 优雅下线流程

```
1. JVM 收到 SIGTERM 信号
2. /health/ready 开始返回 503（负载均衡器不再转发新请求）
3. 活跃请求的 Agent 在下一次 reasoning/acting 前检测到 shutdown
4. GracefulShutdownHook 自动保存 Agent 状态 + InterruptedState 标记
5. 抛出 AgentAbortedException，返回中断响应（含 sessionId）
6. 等待所有请求完成（最多 30 秒）
7. 应用安全退出
```

## 配置项

| 配置项 | 默认值 | 描述 |
|--------|--------|------|
| `agentscope.shutdown.timeout` | 30 | 等待请求完成的超时时间（秒） |
| `agentscope.model.api-key` | - | DashScope API 密钥 |
| `agentscope.model.model-name` | qwen-plus | 使用的模型名称 |

## Kubernetes 集成

```yaml
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: order-agent
    livenessProbe:
      httpGet:
        path: /health
        port: 8080
    readinessProbe:
      httpGet:
        path: /health/ready
        port: 8080
  terminationGracePeriodSeconds: 40
```

## 目录结构

```
graceful-shutdown/
├── pom.xml
├── docker-compose.yml
├── README.md
├── DESIGN.md                              # 设计文档
└── src/main/
    ├── java/io/agentscope/examples/shutdown/
    │   ├── GracefulShutdownApplication.java
    │   ├── config/
    │   │   ├── AgentConfig.java
    │   │   ├── SessionConfig.java
    │   │   └── ShutdownConfig.java
    │   ├── controller/
    │   │   ├── OrderController.java
    │   │   └── HealthController.java
    │   ├── service/
    │   │   └── OrderService.java
    │   ├── tools/
    │   │   └── OrderProcessingTools.java
    │   └── dto/
    │       ├── OrderRequest.java
    │       └── OrderResponse.java
    └── resources/
        ├── application.yml
        └── logback.xml
```
