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
package io.agentscope.examples.shutdown;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application demonstrating graceful shutdown with session state persistence.
 *
 * <p>This demo shows how to handle rolling deployments of AI Agent applications:
 *
 * <ul>
 *   <li>Stop accepting new requests when shutdown signal is received
 *   <li>Wait for active requests to complete (up to 30 seconds)
 *   <li>Interrupt and save state for requests that don't complete in time
 *   <li>Resume processing from saved state on new instance
 * </ul>
 *
 * <h2>Running the Demo:</h2>
 *
 * <pre>
 * 1. Start MySQL: docker-compose up -d
 * 2. Run application: mvn spring-boot:run
 * 3. Submit order: curl -X POST http://localhost:8080/api/orders/process ...
 * 4. Send SIGTERM: kill -TERM $(pgrep -f GracefulShutdownApplication)
 * 5. Restart and resume: curl -X POST http://localhost:8080/api/orders/resume ...
 * </pre>
 */
@SpringBootApplication
public class GracefulShutdownApplication {

    public static void main(String[] args) {
        printBanner();
        SpringApplication.run(GracefulShutdownApplication.class, args);
    }

    private static void printBanner() {
        System.out.println(
                """
                ╔═══════════════════════════════════════════════════════════╗
                ║       AgentScope Graceful Shutdown Demo                   ║
                ║                                                           ║
                ║  Demonstrates rolling deployment with state persistence   ║
                ╚═══════════════════════════════════════════════════════════╝
                """);
    }
}
