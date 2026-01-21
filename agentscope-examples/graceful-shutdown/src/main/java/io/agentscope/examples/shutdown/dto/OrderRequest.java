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

import java.util.List;

/**
 * Request DTO for order processing.
 *
 * <p>The {@code sessionId} is optional:
 * <ul>
 *   <li>If null or empty, a new session will be created</li>
 *   <li>If provided, the service will attempt to resume from saved state</li>
 * </ul>
 *
 * @param sessionId Optional session ID for resuming interrupted orders
 * @param orderId The order identifier
 * @param products List of products in the order
 */
public record OrderRequest(String sessionId, String orderId, List<ProductItem> products) {

    /**
     * Represents a product item in the order.
     */
    public record ProductItem(String id, int quantity) {}
}
