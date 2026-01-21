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
import io.agentscope.core.state.SimpleSessionKey;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context for an active request being tracked by the GracefulShutdownManager.
 *
 * <p>This class encapsulates all information needed to manage a single request during graceful
 * shutdown, including the agent instance, session for state persistence, and timing information.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * RequestContext ctx = new RequestContext(sessionId, agent, session);
 *
 * // During shutdown, interrupt and save state
 * ctx.interruptAndSave();
 * }</pre>
 */
public class RequestContext {

    private static final Logger log = LoggerFactory.getLogger(RequestContext.class);

    private final String sessionId;
    private final AgentBase agent;
    private final Session session;
    private final Instant startTime;
    private volatile boolean interrupted;

    /**
     * Create a new RequestContext.
     *
     * @param sessionId The unique session identifier for this request
     * @param agent The agent instance handling the request
     * @param session The session for state persistence
     */
    public RequestContext(String sessionId, AgentBase agent, Session session) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.session = session;
        this.startTime = Instant.now();
        this.interrupted = false;
    }

    /**
     * Get the session ID.
     *
     * @return The session ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Get the agent instance.
     *
     * @return The agent
     */
    public AgentBase getAgent() {
        return agent;
    }

    /**
     * Get the session for state persistence.
     *
     * @return The session
     */
    public Session getSession() {
        return session;
    }

    /**
     * Get the start time of this request.
     *
     * @return The start time
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Check if this request has been interrupted.
     *
     * @return true if interrupted
     */
    public boolean isInterrupted() {
        return interrupted;
    }

    /**
     * Interrupt the agent and save its current state to the session.
     *
     * <p>This method is called during graceful shutdown when a request has not completed within the
     * timeout period. It first interrupts the agent to stop execution, then saves the agent's
     * current state to the session so it can be resumed later.
     *
     * <p>The method is idempotent - calling it multiple times has no additional effect after the
     * first call.
     */
    public void interruptAndSave() {
        if (interrupted) {
            return;
        }
        interrupted = true;

        log.info("[GracefulShutdown] Interrupting request: {}", sessionId);

        // Interrupt the agent
        agent.interrupt();

        // Save state to session
        try {
            agent.saveTo(session, SimpleSessionKey.of(sessionId));
            log.info("[GracefulShutdown] State saved for session: {}", sessionId);
        } catch (Exception e) {
            log.error("[GracefulShutdown] Failed to save state for session: {}", sessionId, e);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "RequestContext{sessionId='%s', agent=%s, startTime=%s, interrupted=%s}",
                sessionId, agent.getName(), startTime, interrupted);
    }
}
