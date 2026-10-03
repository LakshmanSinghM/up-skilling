package com.upskill.kafka.order.controller;

import com.upskill.kafka.event.OrderCreatedEvent;
import com.upskill.kafka.order.outbox.OutboxRecord;
import com.upskill.kafka.order.outbox.OutboxRepository;
import com.upskill.kafka.order.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OutboxRepository outboxRepository;

    public OrderController(OrderService orderService, OutboxRepository outboxRepository) {
        this.orderService = orderService;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Create single order.
     * Set simulateFailure=true to test the Retry and Dead Letter Topic (DLT) flow.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createOrder(
            @RequestParam(defaultValue = "CUST-001") String customerId,
            @RequestParam(defaultValue = "150.00") BigDecimal amount,
            @RequestParam(defaultValue = "false") boolean simulateFailure) {

        OrderCreatedEvent event = orderService.createOrder(customerId, amount, simulateFailure);
        return ResponseEntity.ok(Map.of(
                "status", "ORDER_CREATED_AND_OUTBOXED",
                "orderId", event.orderId(),
                "eventId", event.eventId(),
                "customerId", event.customerId(),
                "amount", event.amount(),
                "simulateFailure", event.simulateFailure()
        ));
    }

    /**
     * Ordering Test: Generates N sequential orders for the same customer.
     * All N orders will route to the same partition, proving in-order delivery.
     */
    @PostMapping("/ordering-test")
    public ResponseEntity<Map<String, Object>> testOrdering(
            @RequestParam(defaultValue = "CUST-VIP-777") String customerId,
            @RequestParam(defaultValue = "5") int count) {

        List<String> orderIds = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            OrderCreatedEvent event = orderService.createOrder(
                    customerId,
                    BigDecimal.valueOf(100.0 * i),
                    false
            );
            orderIds.add(event.orderId());
        }

        return ResponseEntity.ok(Map.of(
                "message", "Produced " + count + " orders with key=" + customerId,
                "orderIds", orderIds,
                "note", "Inspect Kafka UI or logs: all these events land on the exact same partition!"
        ));
    }

    /**
     * Inspect outbox records to see the Outbox pattern lifecycle in DB.
     */
    @GetMapping("/outbox")
    public ResponseEntity<List<OutboxRecord>> getOutboxRecords() {
        return ResponseEntity.ok(outboxRepository.findAll());
    }
}
