/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.shutdown.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.hook.AgentAbortedException;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.Session;
import io.agentscope.core.shutdown.GracefulShutdownHook;
import io.agentscope.core.shutdown.InterruptedState;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.shutdown.dto.OrderRequest;
import io.agentscope.examples.shutdown.dto.OrderResponse;
import io.agentscope.examples.shutdown.tools.OrderProcessingTools;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Service for processing orders using ReActAgent with graceful shutdown support.
 *
 * <p>This service demonstrates the recommended pattern for graceful shutdown:
 *
 * <ul>
 *   <li>Session ID passed by client (or auto-generated if not provided)
 *   <li>Automatic registration with {@link io.agentscope.core.shutdown.GracefulShutdownManager}
 *   <li>Automatic state persistence on shutdown via {@link GracefulShutdownHook}
 *   <li>Automatic resumption of interrupted orders
 * </ul>
 *
 * <h2>Flow:</h2>
 *
 * <ol>
 *   <li>Client sends request with optional sessionId</li>
 *   <li>If sessionId provided, check for interrupted state and resume</li>
 *   <li>If no sessionId, generate new one and start fresh</li>
 *   <li>On shutdown, state is automatically saved with InterruptedState marker</li>
 *   <li>Client can retry with same sessionId to resume</li>
 * </ol>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String SYS_PROMPT =
            """
            You are an order processing assistant. Your job is to process customer orders by:
            1. First, validate the order using the validate_order tool
            2. Then, check inventory for each product using the check_inventory tool
            3. Next, process the payment using the process_payment tool
            4. Finally, send a confirmation notification using the send_notification tool

            Always process orders in this exact sequence. If any step fails, stop and report the error.
            Be concise in your responses and focus on completing the order processing tasks.
            """;

    private final Model model;
    private final Session session;

    public OrderService(Model model, Session session) {
        this.model = model;
        this.session = session;
    }

    /**
     * Process an order with streaming response.
     *
     * <p>If the request contains a sessionId, the service will check for interrupted state and
     * automatically resume. Otherwise, a new session is created.
     *
     * @param request The order request (sessionId is optional)
     * @return Flux of order responses including the sessionId for future resumption
     */
    public Flux<OrderResponse> processOrder(OrderRequest request) {
        // Determine session ID: use provided or generate new
        String sessionId =
                (request.sessionId() != null && !request.sessionId().isBlank())
                        ? request.sessionId()
                        : "order-" + UUID.randomUUID().toString().substring(0, 8);

        SessionKey sessionKey = SimpleSessionKey.of(sessionId);

        log.info(
                "[OrderService] Processing order, sessionId: {}, orderId: {}, isResume: {}",
                sessionId,
                request.orderId(),
                request.sessionId() != null);

        // Create agent with tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderProcessingTools());

        InMemoryMemory memory = new InMemoryMemory();

        // Create hook with session info (auto-registers with shutdown manager)
        GracefulShutdownHook shutdownHook = new GracefulShutdownHook(session, sessionKey);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("OrderProcessor")
                        .sysPrompt(SYS_PROMPT)
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(10)
                        .checkRunning(false)
                        .hooks(List.of(shutdownHook))
                        .build();

        // Load previous state if exists
        agent.loadIfExists(session, sessionKey);

        // Check for interrupted state (from previous shutdown)
        Optional<InterruptedState> interruptedOpt =
                session.get(sessionKey, InterruptedState.KEY, InterruptedState.class);

        // Determine the message to send
        Msg inputMsg;
        boolean isResume = interruptedOpt.isPresent();

        if (isResume) {
            log.info(
                    "[OrderService] Resuming interrupted order, sessionId: {}, reason: {}",
                    sessionId,
                    interruptedOpt.get().reason());

            inputMsg =
                    Msg.builder()
                            .name("System")
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    "The previous processing was interrupted due"
                                                            + " to: "
                                                            + interruptedOpt.get().reason()
                                                            + ". Please continue from where you"
                                                            + " left off and complete any remaining"
                                                            + " steps.")
                                            .build())
                            .build();
        } else {
            // Build user message for new order
            String productList =
                    request.products().stream()
                            .map(
                                    p ->
                                            String.format(
                                                    "- Product ID: %s, Quantity: %d",
                                                    p.id(), p.quantity()))
                            .collect(Collectors.joining("\n"));

            double totalAmount =
                    request.products().stream().mapToDouble(p -> p.quantity() * 99.99).sum();

            String userMessage =
                    String.format(
                            """
                            Please process the following order:
                            Order ID: %s
                            Products:
                            %s
                            Total Amount: $%.2f

                            Process this order by validating it, checking inventory, \
                            processing payment, and sending notification.
                            """,
                            request.orderId(), productList, totalAmount);

            inputMsg =
                    Msg.builder()
                            .name("Customer")
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text(userMessage).build())
                            .build();
        }

        // Stream events from agent
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(
                                EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                        .build();

        // Prepend resume notification if resuming
        Flux<OrderResponse> responseFlux =
                isResume
                        ? Flux.just(OrderResponse.resumed(sessionId, "Resuming from saved state"))
                        : Flux.empty();

        return responseFlux.concatWith(
                agent.stream(inputMsg, options)
                        .map(event -> eventToResponse(sessionId, event))
                        .doOnComplete(
                                () -> {
                                    log.info(
                                            "[OrderService] Order processing completed, sessionId:"
                                                    + " {}",
                                            sessionId);
                                    // Complete: save state and unregister (but don't delete
                                    // session)
                                    shutdownHook.complete();
                                })
                        .onErrorResume(
                                AgentAbortedException.class,
                                e -> {
                                    log.info(
                                            "[OrderService] Order processing aborted due to"
                                                    + " shutdown, sessionId: {}",
                                            sessionId);
                                    // State already saved by hook, just return response
                                    String message =
                                            e.getReason()
                                                    + ". State saved. Retry with sessionId: "
                                                    + sessionId;
                                    return Flux.just(OrderResponse.interrupted(sessionId, message));
                                })
                        .doOnError(
                                e -> {
                                    if (!(e instanceof AgentAbortedException)) {
                                        log.error(
                                                "[OrderService] Order processing failed, sessionId:"
                                                        + " {}",
                                                sessionId,
                                                e);
                                        // Save state on error for debugging
                                        agent.saveTo(session, sessionKey);
                                    }
                                }));
    }

    /**
     * Check if a session exists.
     *
     * @param sessionId The session ID
     * @return true if session exists
     */
    public boolean sessionExists(String sessionId) {
        return session.exists(SimpleSessionKey.of(sessionId));
    }

    /**
     * Check if a session has interrupted state.
     *
     * @param sessionId The session ID
     * @return Optional containing the interrupted state if present
     */
    public Optional<InterruptedState> getInterruptedState(String sessionId) {
        return session.get(
                SimpleSessionKey.of(sessionId), InterruptedState.KEY, InterruptedState.class);
    }

    private OrderResponse eventToResponse(String sessionId, Event event) {
        String content = extractContent(event);
        String step = extractStep(event);

        if (event.getType() == EventType.AGENT_RESULT) {
            return OrderResponse.completed(sessionId, content);
        }

        return OrderResponse.processing(sessionId, step, content);
    }

    private String extractContent(Event event) {
        if (event.getMessage() == null) {
            return "";
        }
        return event.getMessage().getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    private String extractStep(Event event) {
        return switch (event.getType()) {
            case REASONING -> "reasoning";
            case TOOL_RESULT -> "acting";
            case AGENT_RESULT -> "completed";
            default -> "processing";
        };
    }
}
