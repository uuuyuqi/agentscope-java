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

import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.ShutdownState;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check controller for load balancer integration.
 *
 * <p>Provides health check endpoints that reflect the graceful shutdown state:
 *
 * <ul>
 *   <li>/health - Always returns 200 if application is running
 *   <li>/health/ready - Returns 200 if accepting requests, 503 if shutting down
 * </ul>
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final GracefulShutdownManager shutdownManager;

    public HealthController() {
        this.shutdownManager = GracefulShutdownManager.getInstance();
    }

    /**
     * Liveness probe - indicates if the application is running.
     *
     * @return Always 200 OK if the application is running
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        ShutdownState state = shutdownManager.getState();
        return ResponseEntity.ok(
                Map.of(
                        "status", "UP",
                        "shutdownState", state.name(),
                        "activeRequests", shutdownManager.getActiveRequestCount()));
    }

    /**
     * Readiness probe - indicates if the application is ready to accept requests.
     *
     * <p>Returns:
     *
     * <ul>
     *   <li>200 OK - When accepting requests (RUNNING state)
     *   <li>503 Service Unavailable - When shutting down or terminated
     * </ul>
     *
     * <p>Load balancers should use this endpoint to determine if traffic can be routed to this
     * instance.
     *
     * @return Response based on readiness state
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        if (shutdownManager.isAcceptingRequests()) {
            return ResponseEntity.ok(
                    Map.of(
                            "status", "READY",
                            "shutdownState", shutdownManager.getState().name(),
                            "activeRequests", shutdownManager.getActiveRequestCount()));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            Map.of(
                                    "status",
                                    "NOT_READY",
                                    "shutdownState",
                                    shutdownManager.getState().name(),
                                    "activeRequests",
                                    shutdownManager.getActiveRequestCount(),
                                    "message",
                                    "Service is shutting down"));
        }
    }
}
