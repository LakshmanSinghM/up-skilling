package com.upskill.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable domain event representing a placed order.
 *
 * @param eventId Unique UUID for deduplication and idempotency verification.
 * @param orderId Business order identifier.
 * @param customerId Customer identifier, used as Kafka record key for partition routing.
 * @param amount Order monetary value.
 * @param timestamp Event creation timestamp.
 * @param simulateFailure Flag to intentionally simulate transient or fatal processing exceptions.
 */
public record OrderCreatedEvent(
        String eventId,
        String orderId,
        String customerId,
        BigDecimal amount,
        Instant timestamp,
        boolean simulateFailure
) {}
