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
package io.agentscope.examples.shutdown.dto;

/**
 * Response DTO for order processing.
 */
public record OrderResponse(
        String sessionId, String status, String message, String step, String content) {

    public static OrderResponse processing(String sessionId, String step, String content) {
        return new OrderResponse(sessionId, "processing", null, step, content);
    }

    public static OrderResponse completed(String sessionId, String message) {
        return new OrderResponse(sessionId, "completed", message, null, null);
    }

    public static OrderResponse resumed(String sessionId, String message) {
        return new OrderResponse(sessionId, "resumed", message, null, null);
    }

    public static OrderResponse error(String sessionId, String message) {
        return new OrderResponse(sessionId, "error", message, null, null);
    }

    public static OrderResponse interrupted(String sessionId, String message) {
        return new OrderResponse(sessionId, "interrupted", message, null, null);
    }
}
