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
package io.agentscope.core.hook;

import io.agentscope.core.state.SessionKey;

/**
 * Exception thrown when an agent execution is aborted by a hook.
 *
 * <p>This exception is typically thrown when a {@link PreReasoningEvent} or {@link PreActingEvent}
 * hook calls {@code abort()} to stop the agent from proceeding. Common use cases include:
 *
 * <ul>
 *   <li>Graceful shutdown: Service is shutting down, reject new operations</li>
 *   <li>Rate limiting: Too many requests, abort with retry message</li>
 *   <li>Authorization: User lacks permission for this operation</li>
 * </ul>
 *
 * <p>When this exception is thrown, the agent state may have been automatically saved
 * to the session (if {@code saveState} was requested). The client can use the
 * {@link #getSessionKey()} to resume the operation later.
 *
 * <p><b>Example handling:</b>
 * <pre>{@code
 * agent.call(msg)
 *     .onErrorResume(AgentAbortedException.class, e -> {
 *         if (e.getSessionKey() != null) {
 *             // State was saved, inform client to retry with this sessionKey
 *             return Mono.just(buildRetryResponse(e.getSessionKey(), e.getReason()));
 *         }
 *         return Mono.just(buildErrorResponse(e.getReason()));
 *     });
 * }</pre>
 */
public class AgentAbortedException extends RuntimeException {

    private final String reason;
    private final SessionKey sessionKey;
    private final boolean stateSaved;

    /**
     * Creates an AgentAbortedException with a reason.
     *
     * @param reason The reason for aborting
     */
    public AgentAbortedException(String reason) {
        this(reason, null, false);
    }

    /**
     * Creates an AgentAbortedException with a reason and session key.
     *
     * @param reason The reason for aborting
     * @param sessionKey The session key where state was saved (may be null)
     * @param stateSaved Whether the agent state was saved before aborting
     */
    public AgentAbortedException(String reason, SessionKey sessionKey, boolean stateSaved) {
        super(reason);
        this.reason = reason;
        this.sessionKey = sessionKey;
        this.stateSaved = stateSaved;
    }

    /**
     * Get the reason for aborting.
     *
     * @return The abort reason
     */
    public String getReason() {
        return reason;
    }

    /**
     * Get the session key where state was saved.
     *
     * <p>If non-null, the client can use this key to resume the operation
     * on another instance by calling {@code agent.loadFrom(session, sessionKey)}.
     *
     * @return The session key, or null if state was not saved
     */
    public SessionKey getSessionKey() {
        return sessionKey;
    }

    /**
     * Check if the agent state was saved before aborting.
     *
     * @return true if state was saved, false otherwise
     */
    public boolean isStateSaved() {
        return stateSaved;
    }

    @Override
    public String toString() {
        if (sessionKey != null) {
            return String.format(
                    "AgentAbortedException{reason='%s', sessionKey=%s, stateSaved=%s}",
                    reason, sessionKey, stateSaved);
        }
        return String.format("AgentAbortedException{reason='%s'}", reason);
    }
}
