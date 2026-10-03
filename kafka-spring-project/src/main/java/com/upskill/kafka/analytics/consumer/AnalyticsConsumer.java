package com.upskill.kafka.analytics.consumer;

import com.upskill.kafka.analytics.entity.ProcessedEvent;
import com.upskill.kafka.analytics.repository.ProcessedEventRepository;
import com.upskill.kafka.config.KafkaTopicConfig;
import com.upskill.kafka.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AnalyticsConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsConsumer.class);
    private static final String GROUP_ID = "analytics-group";

    private final ProcessedEventRepository processedEventRepository;

    public AnalyticsConsumer(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Consumer Group 2: analytics-group
     * Operates completely independently from notification-group.
     * Demonstrates the Client-Side Idempotent Consumer Pattern using a PostgreSQL deduplication table.
     */
    @Transactional
    @KafkaListener(
            topics = KafkaTopicConfig.ORDER_EVENTS_TOPIC,
            groupId = GROUP_ID
    )
    public void consume(OrderCreatedEvent event,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("[AnalyticsConsumer] Processing order: {} for customer: {} worth: ${} [P:{}, O:{}]",
                event.orderId(), event.customerId(), event.amount(), partition, offset);

        // 1. Idempotency Check using the database primary key constraint
        try {
            ProcessedEvent record = new ProcessedEvent(event.eventId(), GROUP_ID, Instant.now());
            processedEventRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            // Duplicate detected (at-least-once redelivery)
            log.warn("⚠️ [AnalyticsConsumer] DUPLICATE EVENT DETECTED! EventId: {} for OrderId: {}. Skipping execution to prevent duplicate calculation.",
                    event.eventId(), event.orderId());
            return;
        }

        // 2. Perform idempotent analytics update / ledger calculation
        log.info("📊 [AnalyticsConsumer] Aggregated transaction revenue: +${} for customer: {}. Total state successfully updated.",
                event.amount(), event.customerId());
    }
}
