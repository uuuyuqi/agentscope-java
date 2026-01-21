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
package io.agentscope.examples.shutdown.config;

import io.agentscope.core.shutdown.AgentScopeShutdownHook;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for graceful shutdown.
 *
 * <p>Registers the JVM shutdown hook and configures the shutdown timeout.
 */
@Configuration
public class ShutdownConfig {

    private static final Logger log = LoggerFactory.getLogger(ShutdownConfig.class);

    @Value("${agentscope.shutdown.timeout:30}")
    private int shutdownTimeoutSeconds;

    @PostConstruct
    public void init() {
        Duration timeout = Duration.ofSeconds(shutdownTimeoutSeconds);

        // Register the shutdown hook
        AgentScopeShutdownHook.register(timeout);

        log.info(
                "Graceful shutdown configured with {}s timeout, {} active requests tracked",
                shutdownTimeoutSeconds,
                GracefulShutdownManager.getInstance().getActiveRequestCount());
    }
}
