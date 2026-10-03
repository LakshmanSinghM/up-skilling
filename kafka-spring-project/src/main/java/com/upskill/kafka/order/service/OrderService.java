package com.upskill.kafka.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upskill.kafka.event.OrderCreatedEvent;
import com.upskill.kafka.order.entity.OrderEntity;
import com.upskill.kafka.order.outbox.OutboxRecord;
import com.upskill.kafka.order.outbox.OutboxRepository;
import com.upskill.kafka.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository orderRepository, OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Solves the Dual-Write Problem using the Transactional Outbox Pattern.
     * Both the business state (OrderEntity) and the outbound event (OutboxRecord)
     * are saved inside the SAME atomic database transaction.
     */
    @Transactional
    public OrderCreatedEvent createOrder(String customerId, BigDecimal amount, boolean simulateFailure) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        log.info("Initiating order creation: {} for customer: {} (simulateFailure: {})", orderId, customerId, simulateFailure);

        // 1. Save business entity in database
        OrderEntity order = new OrderEntity(orderId, customerId, amount, "CREATED", now);
        orderRepository.save(order);

        // 2. Prepare domain event
        OrderCreatedEvent event = new OrderCreatedEvent(
                eventId,
                orderId,
                customerId,
                amount,
                now,
                simulateFailure
        );

        // 3. Serialize event to JSON
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order event to JSON", e);
            throw new RuntimeException("Serialization failure", e);
        }

        // 4. Save to Outbox table within the same DB transaction
        OutboxRecord outbox = new OutboxRecord(
                eventId,
                "ORDER",
                orderId,
                customerId, // Partition key to guarantee ordering per customer
                payloadJson,
                "PENDING",
                now
        );
        outboxRepository.save(outbox);

        log.info("Order and Outbox record atomically persisted to DB. OrderId: {}, EventId: {}", orderId, eventId);
        return event;
    }
}
