package com.upskill.kafka.notification.consumer;

import com.upskill.kafka.config.KafkaTopicConfig;
import com.upskill.kafka.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

@Service
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    /**
     * Consumer Group 1: notification-group
     * Concurrency = 3: Spawns 3 dedicated threads to achieve maximum parallel throughput across the 3 partitions.
     * 
     * @RetryableTopic configures non-blocking retry topology:
     * - Fails -> moves to order-events-retry with exponential backoff (1s, 2s, 4s)
     * - Exhausted -> moves to order-events-dlt without blocking healthy messages on main topic!
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            dltTopicSuffix = "-dlt",
            dltStrategy = DltStrategy.FAIL_ON_ERROR
    )
    @KafkaListener(
            topics = KafkaTopicConfig.ORDER_EVENTS_TOPIC,
            groupId = "notification-group",
            concurrency = "3"
    )
    public void consume(OrderCreatedEvent event,
                        @Header(value = "correlation-id", required = false) String correlationId,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("[NotificationConsumer] Received event: {} | Order: {} | Partition: {} | Offset: {} | Topic: {} | Correlation: {}",
                event.eventId(), event.orderId(), partition, offset, topic, correlationId);

        // Simulate failure if flag is set (demonstrating Retry & DLT without stalling other messages)
        if (event.simulateFailure()) {
            log.warn("[NotificationConsumer] Intentionally simulating transient processing exception for order: {}", event.orderId());
            throw new RuntimeException("Simulated processing error for order: " + event.orderId());
        }

        // Business logic: Send notification (SMS/Email)
        log.info("[NotificationConsumer] SUCCESS: Notification dispatched for customer: {} order: {}",
                event.customerId(), event.orderId());
    }

    /**
     * DLT Handler: Catches quarantined events after max retry attempts are exhausted.
     */
    @DltHandler
    public void handleDeadLetterTopic(OrderCreatedEvent event,
                                      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                                      @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                      @Header(KafkaHeaders.OFFSET) long offset) {
        log.error(">>> [DLT QUARANTINE] Order quarantined in DLT! OrderId: {} | EventId: {} | Source Topic: {} | Partition: {} | Offset: {}",
                event.orderId(), event.eventId(), topic, partition, offset);
        // In real systems: Trigger PagerDuty/Slack alert or save to manual triage table
    }
}
