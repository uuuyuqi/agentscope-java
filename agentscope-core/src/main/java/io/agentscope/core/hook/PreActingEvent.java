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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.tool.Toolkit;
import java.util.Objects;

/**
 * Event fired before tool execution.
 *
 * <p><b>Modifiable:</b> Yes - {@link #setToolUse(ToolUseBlock)}
 *
 * <p><b>Abortable:</b> Yes - {@link #abort(String)} to stop execution and optionally save state
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getToolkit()} - The toolkit instance</li>
 *   <li>{@link #getToolUse()} - The tool call to execute (modifiable)</li>
 * </ul>
 *
 * <p><b>Note:</b> This is called once per tool. If the reasoning result contains
 * multiple tool calls, this event fires multiple times.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Validate or modify tool parameters for each tool call</li>
 *   <li>Add authentication or context to individual tool calls</li>
 *   <li>Implement per-tool authorization checks</li>
 *   <li>Log or monitor individual tool invocations</li>
 *   <li>Abort execution during graceful shutdown</li>
 * </ul>
 */
public final class PreActingEvent extends ActingEvent {

    private boolean aborted = false;
    private String abortReason;
    private Session abortSaveSession;
    private SessionKey abortSaveSessionKey;

    /**
     * Constructor for PreActingEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param toolkit The toolkit instance (must not be null)
     * @param toolUse The tool call to execute (must not be null)
     * @throws NullPointerException if agent, toolkit, or toolUse is null
     */
    public PreActingEvent(Agent agent, Toolkit toolkit, ToolUseBlock toolUse) {
        super(HookEventType.PRE_ACTING, agent, toolkit, toolUse);
    }

    /**
     * Modify the tool call (e.g., change parameters).
     *
     * @param toolUse The new tool use block (must not be null)
     * @throws NullPointerException if toolUse is null
     */
    public void setToolUse(ToolUseBlock toolUse) {
        this.toolUse = Objects.requireNonNull(toolUse, "toolUse cannot be null");
    }

    /**
     * Abort the agent execution with a reason.
     *
     * <p>When called, the agent will throw an {@link AgentAbortedException} instead of
     * executing the tool. This is useful for graceful shutdown scenarios where
     * new operations should be rejected.
     *
     * @param reason The reason for aborting (will be included in the exception)
     */
    public void abort(String reason) {
        abort(reason, null, null);
    }

    /**
     * Abort the agent execution with a reason and save state to the specified session.
     *
     * <p>When session and sessionKey are provided, the agent will save its current state
     * to the session before throwing the exception. This allows the operation to be
     * resumed later on another instance by loading from the same session key.
     *
     * @param reason The reason for aborting
     * @param session The session to save state to (may be null to skip saving)
     * @param sessionKey The session key for saving state (may be null to skip saving)
     */
    public void abort(String reason, Session session, SessionKey sessionKey) {
        this.aborted = true;
        this.abortReason = reason;
        this.abortSaveSession = session;
        this.abortSaveSessionKey = sessionKey;
    }

    /**
     * Check if abort has been requested.
     *
     * @return true if {@link #abort(String)} has been called
     */
    public boolean isAborted() {
        return aborted;
    }

    /**
     * Get the abort reason.
     *
     * @return The abort reason, or null if not aborted
     */
    public String getAbortReason() {
        return abortReason;
    }

    /**
     * Check if state should be saved on abort.
     *
     * @return true if session and sessionKey are provided for saving state
     */
    public boolean isSaveStateOnAbort() {
        return abortSaveSession != null && abortSaveSessionKey != null;
    }

    /**
     * Get the session for saving state on abort.
     *
     * @return The session, or null if state should not be saved
     */
    public Session getAbortSaveSession() {
        return abortSaveSession;
    }

    /**
     * Get the session key for saving state on abort.
     *
     * @return The session key, or null if state should not be saved
     */
    public SessionKey getAbortSaveSessionKey() {
        return abortSaveSessionKey;
    }
}
