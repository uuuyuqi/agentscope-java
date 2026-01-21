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

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM shutdown hook for graceful shutdown of AgentScope applications.
 *
 * <p>This hook is registered with the JVM runtime and is called when the JVM receives a shutdown
 * signal (SIGTERM, SIGINT, etc.). It coordinates the graceful shutdown process through the {@link
 * GracefulShutdownManager}.
 *
 * <h2>Usage:</h2>
 *
 * <pre>{@code
 * // Register the shutdown hook at application startup
 * AgentScopeShutdownHook.register();
 *
 * // Or with custom timeout
 * AgentScopeShutdownHook.register(Duration.ofSeconds(60));
 * }</pre>
 *
 * <p>The hook can also be registered automatically by frameworks like Spring Boot through
 * configuration classes.
 */
public class AgentScopeShutdownHook extends Thread {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeShutdownHook.class);

    private static volatile boolean registered = false;

    private final Duration timeout;

    /**
     * Create a new shutdown hook with default timeout.
     *
     * @see GracefulShutdownManager#DEFAULT_SHUTDOWN_TIMEOUT
     */
    public AgentScopeShutdownHook() {
        this(GracefulShutdownManager.DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /**
     * Create a new shutdown hook with custom timeout.
     *
     * @param timeout The maximum time to wait for active requests to complete
     */
    public AgentScopeShutdownHook(Duration timeout) {
        super("AgentScope-ShutdownHook");
        this.timeout = timeout;
    }

    @Override
    public void run() {
        log.info("[ShutdownHook] Received shutdown signal, starting graceful shutdown");

        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();

        // Log current state
        int activeCount = manager.getActiveRequestCount();
        if (activeCount > 0) {
            log.info(
                    "[ShutdownHook] {} active request(s) in progress, waiting up to {}s for"
                            + " completion",
                    activeCount,
                    timeout.getSeconds());
        } else {
            log.info("[ShutdownHook] No active requests, shutdown will be immediate");
        }

        // Initiate shutdown (stops accepting new requests)
        manager.initiateShutdown();

        // Wait for active requests to complete or timeout
        boolean completed = manager.awaitTermination(timeout);

        if (completed) {
            log.info("[ShutdownHook] Graceful shutdown completed, all requests finished normally");
        } else {
            log.warn(
                    "[ShutdownHook] Graceful shutdown completed with timeout, some requests were"
                            + " interrupted and saved");
        }
    }

    /**
     * Register the shutdown hook with the JVM runtime using default timeout.
     *
     * <p>This method is idempotent - calling it multiple times has no additional effect.
     */
    public static synchronized void register() {
        register(GracefulShutdownManager.DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /**
     * Register the shutdown hook with the JVM runtime using custom timeout.
     *
     * <p>This method is idempotent - calling it multiple times has no additional effect.
     *
     * @param timeout The maximum time to wait for active requests to complete
     */
    public static synchronized void register(Duration timeout) {
        if (registered) {
            log.debug("[ShutdownHook] Shutdown hook already registered");
            return;
        }

        AgentScopeShutdownHook hook = new AgentScopeShutdownHook(timeout);
        Runtime.getRuntime().addShutdownHook(hook);
        registered = true;

        // Also set the timeout on the manager
        GracefulShutdownManager.getInstance().setShutdownTimeout(timeout);

        log.info(
                "[ShutdownHook] Registered AgentScope shutdown hook with {}s timeout",
                timeout.getSeconds());
    }

    /**
     * Check if the shutdown hook has been registered.
     *
     * @return true if registered
     */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * Reset registration status (for testing purposes only).
     *
     * <p><strong>Warning:</strong> This method should only be used in tests. The actual JVM
     * shutdown hook cannot be unregistered.
     */
    static synchronized void resetRegistrationStatus() {
        registered = false;
    }
}
