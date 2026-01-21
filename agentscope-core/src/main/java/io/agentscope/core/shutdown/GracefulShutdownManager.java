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
package io.agentscope.core.shutdown;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.session.Session;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager for graceful shutdown of AgentScope applications.
 *
 * <p>This class provides centralized management of active requests during application shutdown. It
 * tracks all active agent requests and ensures they either complete normally or have their state
 * saved before the application terminates.
 *
 * <h2>Key Features:</h2>
 *
 * <ul>
 *   <li>Request tracking: Register and unregister active requests by session ID
 *   <li>Graceful shutdown: Stop accepting new requests while waiting for active ones to complete
 *   <li>State persistence: Automatically save state for requests that don't complete within timeout
 *   <li>Health check integration: Provide status for load balancer health checks
 * </ul>
 *
 * <h2>Shutdown Flow:</h2>
 *
 * <ol>
 *   <li>JVM receives SIGTERM signal
 *   <li>{@link #initiateShutdown()} is called, state changes to SHUTTING_DOWN
 *   <li>{@link #awaitTermination(Duration)} waits for active requests to complete
 *   <li>After timeout, remaining requests are interrupted and their state is saved
 *   <li>State changes to TERMINATED
 * </ol>
 *
 * <h2>Usage Example:</h2>
 *
 * <pre>{@code
 * GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
 *
 * // In request handling code
 * if (!manager.isAcceptingRequests()) {
 *     throw new ServiceUnavailableException("Service is shutting down");
 * }
 *
 * manager.registerRequest(sessionId, agent, session);
 * try {
 *     // Process request
 * } finally {
 *     manager.unregisterRequest(sessionId);
 * }
 * }</pre>
 */
public class GracefulShutdownManager {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownManager.class);

    /** Default shutdown timeout in seconds. */
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    /** Singleton instance holder for lazy initialization. */
    private static class InstanceHolder {
        private static final GracefulShutdownManager INSTANCE = new GracefulShutdownManager();
    }

    /** Active requests indexed by session ID. */
    private final ConcurrentHashMap<String, RequestContext> activeRequests;

    /** Current shutdown state. */
    private final AtomicReference<ShutdownState> state;

    /** Shutdown timeout duration. */
    private volatile Duration shutdownTimeout;

    /** Latch for coordinating shutdown completion. */
    private volatile CountDownLatch shutdownLatch;

    /** Private constructor for singleton pattern. */
    private GracefulShutdownManager() {
        this.activeRequests = new ConcurrentHashMap<>();
        this.state = new AtomicReference<>(ShutdownState.RUNNING);
        this.shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
    }

    /**
     * Get the singleton instance of GracefulShutdownManager.
     *
     * @return The singleton instance
     */
    public static GracefulShutdownManager getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Set the shutdown timeout duration.
     *
     * @param timeout The timeout duration
     */
    public void setShutdownTimeout(Duration timeout) {
        this.shutdownTimeout = timeout;
    }

    /**
     * Get the shutdown timeout duration.
     *
     * @return The timeout duration
     */
    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    /**
     * Register an active request for tracking.
     *
     * <p>This method should be called at the start of request processing, after validating that the
     * service is accepting requests.
     *
     * @param sessionId The unique session identifier
     * @param agent The agent handling the request
     * @param session The session for state persistence
     * @throws IllegalStateException if the service is not accepting requests
     */
    public void registerRequest(String sessionId, AgentBase agent, Session session) {
        if (!isAcceptingRequests()) {
            throw new IllegalStateException("Service is shutting down, not accepting new requests");
        }

        RequestContext context = new RequestContext(sessionId, agent, session);
        RequestContext existing = activeRequests.putIfAbsent(sessionId, context);
        if (existing != null) {
            log.warn(
                    "[GracefulShutdown] Request already registered for session: {}, overwriting",
                    sessionId);
            activeRequests.put(sessionId, context);
        }

        log.debug(
                "[GracefulShutdown] Registered request: {}, active count: {}",
                sessionId,
                activeRequests.size());
    }

    /**
     * Unregister a request after completion.
     *
     * <p>This method should be called in a finally block to ensure cleanup even if the request
     * fails.
     *
     * @param sessionId The session identifier to unregister
     */
    public void unregisterRequest(String sessionId) {
        RequestContext removed = activeRequests.remove(sessionId);
        if (removed != null) {
            log.debug(
                    "[GracefulShutdown] Unregistered request: {}, active count: {}",
                    sessionId,
                    activeRequests.size());

            // If shutting down and no more active requests, signal completion
            if (state.get() == ShutdownState.SHUTTING_DOWN && activeRequests.isEmpty()) {
                CountDownLatch latch = this.shutdownLatch;
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
    }

    /**
     * Check if the service is accepting new requests.
     *
     * @return true if accepting requests, false if shutting down or terminated
     */
    public boolean isAcceptingRequests() {
        return state.get() == ShutdownState.RUNNING;
    }

    /**
     * Get the current shutdown state.
     *
     * @return The current state
     */
    public ShutdownState getState() {
        return state.get();
    }

    /**
     * Get the number of active requests.
     *
     * @return The active request count
     */
    public int getActiveRequestCount() {
        return activeRequests.size();
    }

    /**
     * Get the RequestContext for a given agent.
     *
     * <p>This method searches through all active requests to find the one associated with the given
     * agent instance. This is useful for hooks that need to access session information for state
     * saving during abort.
     *
     * @param agent The agent to find the context for
     * @return The RequestContext if found, or null if no matching context exists
     */
    public RequestContext getRequestContextByAgent(AgentBase agent) {
        if (agent == null) {
            return null;
        }
        for (RequestContext context : activeRequests.values()) {
            if (context.getAgent() == agent) {
                return context;
            }
        }
        return null;
    }

    /**
     * Initiate graceful shutdown.
     *
     * <p>This method transitions the state to SHUTTING_DOWN, which causes {@link
     * #isAcceptingRequests()} to return false. New requests will be rejected with an error.
     *
     * <p>This method is idempotent - calling it multiple times has no additional effect.
     */
    public void initiateShutdown() {
        if (state.compareAndSet(ShutdownState.RUNNING, ShutdownState.SHUTTING_DOWN)) {
            log.info(
                    "[GracefulShutdown] Shutdown initiated, {} active requests",
                    activeRequests.size());
            this.shutdownLatch = new CountDownLatch(1);
        }
    }

    /**
     * Wait for all active requests to complete or timeout.
     *
     * <p>This method blocks until either:
     *
     * <ul>
     *   <li>All active requests complete normally
     *   <li>The timeout expires
     * </ul>
     *
     * <p>If the timeout expires, all remaining active requests are interrupted and their state is
     * saved to their respective sessions.
     *
     * @param timeout The maximum time to wait
     * @return true if all requests completed normally, false if timeout occurred
     */
    public boolean awaitTermination(Duration timeout) {
        if (state.get() != ShutdownState.SHUTTING_DOWN) {
            log.warn("[GracefulShutdown] awaitTermination called but not in SHUTTING_DOWN state");
            return true;
        }

        // If no active requests, complete immediately
        if (activeRequests.isEmpty()) {
            state.set(ShutdownState.TERMINATED);
            log.info("[GracefulShutdown] No active requests, shutdown complete");
            return true;
        }

        log.info(
                "[GracefulShutdown] Waiting up to {}s for {} active requests to complete",
                timeout.getSeconds(),
                activeRequests.size());

        try {
            boolean completed = shutdownLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (completed && activeRequests.isEmpty()) {
                state.set(ShutdownState.TERMINATED);
                log.info("[GracefulShutdown] All requests completed normally");
                return true;
            }

            // Timeout - interrupt and save remaining requests
            log.warn(
                    "[GracefulShutdown] Timeout reached, {} requests still active",
                    activeRequests.size());
            interruptAndSaveAll();
            state.set(ShutdownState.TERMINATED);
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[GracefulShutdown] Interrupted while waiting for requests to complete");
            interruptAndSaveAll();
            state.set(ShutdownState.TERMINATED);
            return false;
        }
    }

    /**
     * Interrupt all active requests and save their state.
     *
     * <p>This method is called when the shutdown timeout expires and there are still active
     * requests.
     */
    private void interruptAndSaveAll() {
        log.info(
                "[GracefulShutdown] Interrupting and saving {} active requests",
                activeRequests.size());

        for (RequestContext context : activeRequests.values()) {
            try {
                context.interruptAndSave();
            } catch (Exception e) {
                log.error(
                        "[GracefulShutdown] Error interrupting/saving request: {}",
                        context.getSessionId(),
                        e);
            }
        }

        activeRequests.clear();
    }

    /**
     * Reset the manager to initial state (for testing purposes).
     *
     * <p><strong>Warning:</strong> This method should only be used in tests.
     */
    public void reset() {
        activeRequests.clear();
        state.set(ShutdownState.RUNNING);
        shutdownLatch = null;
        shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
        log.debug("[GracefulShutdown] Manager reset to initial state");
    }
}
