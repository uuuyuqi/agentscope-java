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
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Event fired before LLM reasoning.
 *
 * <p><b>Modifiable:</b> Yes - {@link #setInputMessages(List)}
 *
 * <p><b>Abortable:</b> Yes - {@link #abort(String)} to stop execution and optionally save state
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getModelName()} - The model name (e.g., "qwen-plus")</li>
 *   <li>{@link #getGenerateOptions()} - The generation options (temperature, etc.)</li>
 *   <li>{@link #getInputMessages()} - Messages to send to LLM (modifiable)</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Inject hints or additional context into the prompt</li>
 *   <li>Filter or modify existing messages</li>
 *   <li>Add system instructions dynamically</li>
 *   <li>Log reasoning input</li>
 *   <li>Abort execution during graceful shutdown</li>
 * </ul>
 */
public final class PreReasoningEvent extends ReasoningEvent {

    private List<Msg> inputMessages;
    private GenerateOptions overriddenGenerateOptions;
    private boolean aborted = false;
    private String abortReason;
    private Session abortSaveSession;
    private SessionKey abortSaveSessionKey;

    /**
     * Constructor for PreReasoningEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param modelName The model name (must not be null)
     * @param generateOptions The generation options (may be null)
     * @param inputMessages The messages to send to LLM (must not be null)
     * @throws NullPointerException if agent, modelName, or inputMessages is null
     */
    public PreReasoningEvent(
            Agent agent,
            String modelName,
            GenerateOptions generateOptions,
            List<Msg> inputMessages) {
        super(HookEventType.PRE_REASONING, agent, modelName, generateOptions);
        this.inputMessages =
                new ArrayList<>(
                        Objects.requireNonNull(inputMessages, "inputMessages cannot be null"));
    }

    /**
     * Get the messages that will be sent to LLM for reasoning.
     *
     * @return The input messages
     */
    public List<Msg> getInputMessages() {
        return inputMessages;
    }

    /**
     * Modify the messages to send to LLM.
     *
     * @param inputMessages The new message list (must not be null)
     * @throws NullPointerException if inputMessages is null
     */
    public void setInputMessages(List<Msg> inputMessages) {
        this.inputMessages = Objects.requireNonNull(inputMessages, "inputMessages cannot be null");
    }

    /**
     * Get the effective generation options.
     *
     * <p>Returns the overridden options if set via {@link #setGenerateOptions(GenerateOptions)},
     * otherwise returns the original options from the parent class.
     *
     * @return The effective generation options
     */
    public GenerateOptions getEffectiveGenerateOptions() {
        return overriddenGenerateOptions != null
                ? overriddenGenerateOptions
                : super.getGenerateOptions();
    }

    /**
     * Set custom generation options for this reasoning call.
     *
     * <p>This allows hooks to override the default generation options, for example to set
     * a specific tool_choice for structured output.
     *
     * @param generateOptions The custom generation options
     */
    public void setGenerateOptions(GenerateOptions generateOptions) {
        this.overriddenGenerateOptions = generateOptions;
    }

    /**
     * Abort the agent execution with a reason.
     *
     * <p>When called, the agent will throw an {@link AgentAbortedException} instead of
     * proceeding with reasoning. This is useful for graceful shutdown scenarios where
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
