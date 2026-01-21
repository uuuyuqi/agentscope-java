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
package io.agentscope.examples.shutdown.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tools for order processing demonstration.
 *
 * <p>Each tool simulates a time-consuming operation and supports interruption for graceful
 * shutdown. The tools emit progress updates via ToolEmitter for streaming responses.
 */
public class OrderProcessingTools {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingTools.class);

    /**
     * Validate an order.
     *
     * @param orderId The order ID to validate
     * @param toolEmitter Emitter for streaming progress updates
     * @return Validation result
     */
    @Tool(
            name = "validate_order",
            description =
                    "Validates an order by checking order ID format, customer information, and"
                            + " basic order data")
    public String validateOrder(String orderId, ToolEmitter toolEmitter) {
        log.info("[Tool] validate_order started for order: {}", orderId);

        // Simulate 2 seconds of processing with progress updates
        for (int i = 1; i <= 4; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("[Tool] validate_order interrupted for order: {}", orderId);
                return "Validation interrupted - order: " + orderId;
            }

            try {
                Thread.sleep(500);
                toolEmitter.emit(ToolResultBlock.text("Validating order... " + (i * 25) + "%"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Validation interrupted - order: " + orderId;
            }
        }

        log.info("[Tool] validate_order completed for order: {}", orderId);
        return "Order " + orderId + " validated successfully. Customer verified, order data valid.";
    }

    /**
     * Check inventory for products.
     *
     * @param productId The product ID to check
     * @param quantity The quantity needed
     * @param toolEmitter Emitter for streaming progress updates
     * @return Inventory check result
     */
    @Tool(
            name = "check_inventory",
            description = "Checks if the requested quantity of a product is available in inventory")
    public String checkInventory(String productId, int quantity, ToolEmitter toolEmitter) {
        log.info(
                "[Tool] check_inventory started for product: {}, quantity: {}",
                productId,
                quantity);

        // Simulate 2 seconds of processing
        for (int i = 1; i <= 4; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("[Tool] check_inventory interrupted for product: {}", productId);
                return "Inventory check interrupted - product: " + productId;
            }

            try {
                Thread.sleep(500);
                toolEmitter.emit(ToolResultBlock.text("Checking inventory... " + (i * 25) + "%"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Inventory check interrupted - product: " + productId;
            }
        }

        // Simulate available inventory
        int available = 100;
        boolean inStock = quantity <= available;

        log.info(
                "[Tool] check_inventory completed for product: {}, in stock: {}",
                productId,
                inStock);

        if (inStock) {
            return String.format(
                    "Product %s has %d units available. Requested quantity %d is in stock.",
                    productId, available, quantity);
        } else {
            return String.format(
                    "Product %s has only %d units available. Requested quantity %d exceeds stock.",
                    productId, available, quantity);
        }
    }

    /**
     * Process payment for an order.
     *
     * @param orderId The order ID
     * @param amount The payment amount
     * @param toolEmitter Emitter for streaming progress updates
     * @return Payment processing result
     */
    @Tool(
            name = "process_payment",
            description =
                    "Processes payment for an order. This is a critical operation that takes longer"
                            + " to complete.")
    public String processPayment(String orderId, double amount, ToolEmitter toolEmitter) {
        log.info("[Tool] process_payment started for order: {}, amount: {}", orderId, amount);

        // Simulate 3 seconds of payment processing (longer operation)
        String[] stages = {
            "Connecting to payment gateway...",
            "Verifying payment details...",
            "Authorizing transaction...",
            "Processing payment...",
            "Confirming transaction...",
            "Finalizing payment..."
        };

        for (int i = 0; i < stages.length; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("[Tool] process_payment interrupted for order: {}", orderId);
                return "Payment processing interrupted - order: "
                        + orderId
                        + ". Transaction rolled back.";
            }

            try {
                toolEmitter.emit(ToolResultBlock.text(stages[i]));
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Payment processing interrupted - order: "
                        + orderId
                        + ". Transaction rolled back.";
            }
        }

        log.info("[Tool] process_payment completed for order: {}", orderId);
        return String.format(
                "Payment of $%.2f for order %s processed successfully. Transaction ID: TXN-%s",
                amount, orderId, System.currentTimeMillis());
    }

    /**
     * Send notification about order status.
     *
     * @param orderId The order ID
     * @param message The notification message
     * @param toolEmitter Emitter for streaming progress updates
     * @return Notification result
     */
    @Tool(
            name = "send_notification",
            description = "Sends a notification to the customer about their order status")
    public String sendNotification(String orderId, String message, ToolEmitter toolEmitter) {
        log.info("[Tool] send_notification started for order: {}", orderId);

        // Simulate 1 second of notification sending
        for (int i = 1; i <= 2; i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("[Tool] send_notification interrupted for order: {}", orderId);
                return "Notification sending interrupted - order: " + orderId;
            }

            try {
                Thread.sleep(500);
                toolEmitter.emit(ToolResultBlock.text("Sending notification... " + (i * 50) + "%"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Notification sending interrupted - order: " + orderId;
            }
        }

        log.info("[Tool] send_notification completed for order: {}", orderId);
        return String.format(
                "Notification sent for order %s: '%s'. Delivery confirmed via email and SMS.",
                orderId, message);
    }
}
