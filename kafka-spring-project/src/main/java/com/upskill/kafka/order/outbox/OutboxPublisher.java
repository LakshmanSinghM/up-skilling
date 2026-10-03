package com.upskill.kafka.order.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upskill.kafka.config.KafkaTopicConfig;
import com.upskill.kafka.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate,
                           ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Polls the Outbox table every 1000ms for PENDING records and dispatches them to Kafka.
     * In high-throughput production, CDC (Debezium) tailing WAL is used; this polling publisher
     * provides a clear, runnable in-app simulation of the Outbox publishing lifecycle.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingOutboxEvents() {
        List<OutboxRecord> pendingRecords = outboxRepository.findTop20ByStatusOrderByCreatedAtAsc("PENDING");

        if (pendingRecords.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox records to publish to Kafka", pendingRecords.size());

        for (OutboxRecord record : pendingRecords) {
            try {
                OrderCreatedEvent event = objectMapper.readValue(record.getPayload(), OrderCreatedEvent.class);
                String correlationId = UUID.randomUUID().toString();

                Message<OrderCreatedEvent> message = MessageBuilder
                        .withPayload(event)
                        .setHeader(KafkaHeaders.TOPIC, KafkaTopicConfig.ORDER_EVENTS_TOPIC)
                        .setHeader(KafkaHeaders.KEY, record.getPartitionKey()) // Guarantee per-customer partition ordering
                        .setHeader("correlation-id", correlationId)
                        .setHeader("event-id", record.getEventId())
                        .build();

                kafkaTemplate.send(message)
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                log.info("Successfully published event: {} to partition: {} offset: {}",
                                        record.getEventId(),
                                        result.getRecordMetadata().partition(),
                                        result.getRecordMetadata().offset());
                                record.setStatus("SENT");
                                record.setSentAt(Instant.now());
                                outboxRepository.save(record);
                            } else {
                                log.error("Failed to publish outbox event: {} to Kafka", record.getEventId(), ex);
                                record.setStatus("FAILED");
                                outboxRepository.save(record);
                            }
                        });

            } catch (Exception e) {
                log.error("Exception processing outbox record ID: {}", record.getId(), e);
                record.setStatus("FAILED");
                outboxRepository.save(record);
            }
        }
    }
}
