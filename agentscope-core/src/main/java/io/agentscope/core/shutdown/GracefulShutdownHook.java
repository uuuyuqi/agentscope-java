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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that enables graceful shutdown behavior for agents.
 *
 * <p>This hook provides automatic integration with {@link GracefulShutdownManager}:
 *
 * <ul>
 *   <li><b>Auto-registration:</b> Automatically registers the agent with shutdown manager on first
 *       event
 *   <li><b>Shutdown detection:</b> Checks if service is shutting down before each reasoning/acting
 *   <li><b>State persistence:</b> Saves agent state and {@link InterruptedState} marker when
 *       aborting
 *   <li><b>Automatic resume:</b> Detects interrupted state and injects resume prompt automatically
 * </ul>
 *
 * <h2>Usage:</h2>
 *
 * <p>The hook automatically handles resume logic. Business layer only needs to:
 *
 * <pre>{@code
 * // Create agent with GracefulShutdownHook
 * GracefulShutdownHook hook = new GracefulShutdownHook(session, sessionKey);
 * ReActAgent agent = ReActAgent.builder()
 *     .name("MyAgent")
 *     .model(model)
 *     .hooks(List.of(hook))
 *     .build();
 *
 * // Load previous state if exists
 * agent.loadIfExists(session, sessionKey);
 *
 * // Just send the user message - resume is handled automatically by the hook
 * // If there was an interrupted state, the hook will inject a SYSTEM message
 * // prompting the LLM to continue from where it left off
 * return agent.stream(userMessage)
 *     .doOnComplete(() -> hook.complete());
 * }</pre>
 *
 * <h2>Automatic Resume:</h2>
 *
 * <p>When the agent loads from a session that has an {@link InterruptedState} marker,
 * this hook automatically:
 * <ol>
 *   <li>Detects the interrupted state on first {@link PreReasoningEvent}</li>
 *   <li>Injects a SYSTEM message telling the LLM to continue from where it left off</li>
 *   <li>Clears the interrupted state marker</li>
 * </ol>
 *
 * <p>This allows business layer to be completely unaware of resume logic.
 *
 * <h2>Completion:</h2>
 *
 * <p>When execution completes successfully, call {@link #complete()} to:
 *
 * <ul>
 *   <li>Clear the {@link InterruptedState} marker
 *   <li>Save agent state to session
 *   <li>Unregister from shutdown manager
 * </ul>
 */
public class GracefulShutdownHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHook.class);

    private static final String ABORT_REASON = "Service is shutting down, please retry later";

    private final Session session;
    private final SessionKey sessionKey;
    private final GracefulShutdownManager shutdownManager;

    private volatile AgentBase registeredAgent;
    private volatile boolean registered = false;
    private volatile boolean resumed = false;

    /**
     * Creates a GracefulShutdownHook with session information.
     *
     * <p>The hook will automatically register the agent with {@link GracefulShutdownManager} on
     * first use, and save state to the provided session when aborting.
     *
     * @param session The session for state persistence
     * @param sessionKey The session key for this execution
     */
    public GracefulShutdownHook(Session session, SessionKey sessionKey) {
        this(session, sessionKey, GracefulShutdownManager.getInstance());
    }

    /**
     * Creates a GracefulShutdownHook with custom shutdown manager.
     *
     * @param session The session for state persistence
     * @param sessionKey The session key for this execution
     * @param shutdownManager The shutdown manager to use
     */
    public GracefulShutdownHook(
            Session session, SessionKey sessionKey, GracefulShutdownManager shutdownManager) {
        this.session = session;
        this.sessionKey = sessionKey;
        this.shutdownManager = shutdownManager;
    }

    /**
     * Get the session key associated with this hook.
     *
     * @return The session key
     */
    public SessionKey getSessionKey() {
        return sessionKey;
    }

    /**
     * Complete the execution successfully.
     *
     * <p>This method should be called when the agent execution completes normally. It:
     *
     * <ul>
     *   <li>Clears the {@link InterruptedState} marker from session
     *   <li>Saves agent state to session
     *   <li>Unregisters from shutdown manager
     * </ul>
     *
     * <p>Note: This does NOT delete the session. The session is preserved for future interactions.
     */
    public void complete() {
        // Clear interrupted state marker
        session.delete(sessionKey, InterruptedState.KEY);

        // Save agent state
        if (registeredAgent != null) {
            registeredAgent.saveTo(session, sessionKey);
            log.debug(
                    "[GracefulShutdownHook] Execution completed, state saved for session: {}",
                    sessionKey);
        }

        // Unregister from shutdown manager
        if (registered) {
            shutdownManager.unregisterRequest(sessionKey.toString());
            registered = false;
        }
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Handle resume and auto-register on first PreReasoning event
        if (event instanceof PreReasoningEvent preReasoningEvent) {
            ensureRegistered(event.getAgent());
            if (!resumed) {
                handleResumeIfNeeded(preReasoningEvent);
            }
        }

        // Check if we're shutting down
        if (!shutdownManager.isAcceptingRequests()) {
            if (event instanceof PreReasoningEvent preReasoningEvent) {
                handleShutdown(preReasoningEvent);
            } else if (event instanceof PreActingEvent preActingEvent) {
                handleShutdown(preActingEvent);
            }
        }

        return Mono.just(event);
    }

    /**
     * Handle resume if InterruptedState exists in session.
     *
     * <p>When resuming from a previous interrupted execution, this method:
     * <ul>
     *   <li>Detects the {@link InterruptedState} marker in session</li>
     *   <li>Injects a SYSTEM message to prompt the LLM to continue</li>
     *   <li>Clears the InterruptedState marker</li>
     * </ul>
     *
     * <p>This allows the business layer to be completely unaware of resume logic.
     * The agent will automatically continue from where it left off based on its
     * loaded conversation history.
     */
    private void handleResumeIfNeeded(PreReasoningEvent event) {
        Optional<InterruptedState> interruptedOpt =
                session.get(sessionKey, InterruptedState.KEY, InterruptedState.class);

        if (interruptedOpt.isPresent()) {
            InterruptedState state = interruptedOpt.get();

            log.info(
                    "[GracefulShutdownHook] Detected interrupted state for session: {}, "
                            + "interrupted at: {}, reason: {}. Injecting resume message.",
                    sessionKey,
                    state.interruptedAt(),
                    state.reason());

            // Build resume prompt message
            Msg resumeMsg =
                    Msg.builder()
                            .name("System")
                            .role(MsgRole.SYSTEM)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    "IMPORTANT: Your previous execution was"
                                                            + " interrupted at "
                                                            + state.interruptedAt()
                                                            + " due to: "
                                                            + state.reason()
                                                            + ". You have already made progress."
                                                            + " Review your conversation history"
                                                            + " and continue from where you left"
                                                            + " off. Do not restart from the"
                                                            + " beginning.")
                                            .build())
                            .build();

            // Inject resume message into input messages
            List<Msg> inputMsgs = new ArrayList<>(event.getInputMessages());
            inputMsgs.add(resumeMsg);
            event.setInputMessages(inputMsgs);

            // Clear interrupted state marker
            session.delete(sessionKey, InterruptedState.KEY);
            resumed = true;
        }
    }

    /**
     * Ensure the agent is registered with shutdown manager.
     */
    private synchronized void ensureRegistered(Agent agent) {
        if (registered) {
            return;
        }

        if (agent instanceof AgentBase agentBase) {
            this.registeredAgent = agentBase;
            shutdownManager.registerRequest(sessionKey.toString(), agentBase, session);
            registered = true;
            log.debug("[GracefulShutdownHook] Auto-registered agent for session: {}", sessionKey);
        }
    }

    /**
     * Handle shutdown for PreReasoningEvent.
     */
    private void handleShutdown(PreReasoningEvent event) {
        log.info(
                "[GracefulShutdownHook] Aborting reasoning for session: {}, saving state",
                sessionKey);

        // Save interrupted state marker
        session.save(sessionKey, InterruptedState.KEY, InterruptedState.now(ABORT_REASON));

        // Abort with session info for state saving
        event.abort(ABORT_REASON, session, sessionKey);
    }

    /**
     * Handle shutdown for PreActingEvent.
     */
    private void handleShutdown(PreActingEvent event) {
        log.info(
                "[GracefulShutdownHook] Aborting tool execution for session: {}, saving state",
                sessionKey);

        // Save interrupted state marker
        session.save(sessionKey, InterruptedState.KEY, InterruptedState.now(ABORT_REASON));

        // Abort with session info for state saving
        event.abort(ABORT_REASON, session, sessionKey);
    }

    @Override
    public int priority() {
        // Highest priority - should run before any other hooks
        return 0;
    }
}
