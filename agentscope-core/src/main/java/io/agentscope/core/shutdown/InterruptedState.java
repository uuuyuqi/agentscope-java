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

import io.agentscope.core.state.State;
import java.time.Instant;

/**
 * State marker indicating that an agent execution was interrupted during graceful shutdown.
 *
 * <p>This state is saved to the session when:
 * <ul>
 *   <li>The service receives a shutdown signal (SIGTERM)</li>
 *   <li>The agent is in the middle of processing a request</li>
 *   <li>The {@link GracefulShutdownHook} triggers an abort</li>
 * </ul>
 *
 * <p>When a client reconnects with the same sessionId, the presence of this state indicates
 * that the previous execution was interrupted and should be resumed.
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Check for interrupted state when loading session
 * Optional<InterruptedState> interrupted = session.get(
 *     sessionKey, InterruptedState.KEY, InterruptedState.class);
 *
 * if (interrupted.isPresent()) {
 *     // Previous execution was interrupted, resume it
 *     agent.loadFrom(session, sessionKey);
 *     return agent.stream(resumeMessage);
 * }
 * }</pre>
 *
 * @param reason The reason for interruption (e.g., "Service is shutting down")
 * @param interruptedAt The timestamp when the interruption occurred
 */
public record InterruptedState(String reason, Instant interruptedAt) implements State {

    /** The key used to store this state in the session. */
    public static final String KEY = "interrupted_state";

    /**
     * Create an InterruptedState with the current timestamp.
     *
     * @param reason The reason for interruption
     * @return A new InterruptedState instance
     */
    public static InterruptedState now(String reason) {
        return new InterruptedState(reason, Instant.now());
    }
}
