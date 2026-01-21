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
package io.agentscope.examples.shutdown.controller;

import io.agentscope.core.shutdown.InterruptedState;
import io.agentscope.examples.shutdown.dto.OrderRequest;
import io.agentscope.examples.shutdown.dto.OrderResponse;
import io.agentscope.examples.shutdown.service.OrderService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for order processing.
 *
 * <p>Provides a unified endpoint for processing orders that handles both new orders and resuming
 * interrupted ones.
 *
 * <h2>Usage:</h2>
 *
 * <p><b>New order (no sessionId):</b>
 *
 * <pre>{@code
 * POST /api/orders/process
 * {
 *   "orderId": "ORD-001",
 *   "products": [{"id": "PROD-1", "quantity": 2}]
 * }
 * }</pre>
 *
 * <p><b>Resume interrupted order (with sessionId):</b>
 *
 * <pre>{@code
 * POST /api/orders/process
 * {
 *   "sessionId": "order-abc12345",
 *   "orderId": "ORD-001",
 *   "products": [{"id": "PROD-1", "quantity": 2}]
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Process an order (new or resume).
     *
     * <p>If the request contains a sessionId, the service will check for interrupted state and
     * automatically resume the order. Otherwise, a new session is created.
     *
     * <p>The response stream includes the sessionId which clients should save for potential
     * resumption.
     *
     * @param request The order request (sessionId is optional)
     * @return SSE stream of order processing events
     */
    @PostMapping(value = "/process", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<OrderResponse> processOrder(@RequestBody OrderRequest request) {
        log.info(
                "[Controller] Received order request: orderId={}, sessionId={}",
                request.orderId(),
                request.sessionId());
        return orderService.processOrder(request);
    }

    /**
     * Check session status.
     *
     * <p>Returns information about the session including whether it exists and if it has
     * interrupted state that needs resumption.
     *
     * @param sessionId The session ID to check
     * @return Response with session status
     */
    @GetMapping("/{sessionId}")
    public Mono<OrderResponse> checkSession(@PathVariable String sessionId) {
        boolean exists = orderService.sessionExists(sessionId);

        if (!exists) {
            return Mono.just(
                    new OrderResponse(sessionId, "not_found", "Session not found", null, null));
        }

        Optional<InterruptedState> interrupted = orderService.getInterruptedState(sessionId);
        if (interrupted.isPresent()) {
            String message =
                    String.format(
                            "Session interrupted at %s. Reason: %s. "
                                    + "Include this sessionId in your next request to resume.",
                            interrupted.get().interruptedAt(), interrupted.get().reason());
            return Mono.just(new OrderResponse(sessionId, "interrupted", message, null, null));
        }

        return Mono.just(new OrderResponse(sessionId, "found", "Session exists", null, null));
    }
}
