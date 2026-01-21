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

/**
 * Represents the shutdown state of the GracefulShutdownManager.
 *
 * <p>The state transitions follow: RUNNING -> SHUTTING_DOWN -> TERMINATED
 *
 * <ul>
 *   <li>{@link #RUNNING}: Normal operation, accepting new requests
 *   <li>{@link #SHUTTING_DOWN}: Shutdown initiated, rejecting new requests, waiting for active
 *       requests to complete
 *   <li>{@link #TERMINATED}: All requests completed or timed out, shutdown complete
 * </ul>
 */
public enum ShutdownState {

    /** Normal operation state. New requests are accepted. */
    RUNNING,

    /**
     * Shutdown in progress. New requests are rejected with 503. Waiting for active requests to
     * complete or timeout.
     */
    SHUTTING_DOWN,

    /** Shutdown complete. All active requests have been saved or completed. */
    TERMINATED
}
