package com.upskill.kafka.order.outbox;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String partitionKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String status; // PENDING, SENT, FAILED

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    public OutboxRecord() {}

    public OutboxRecord(String eventId, String aggregateType, String aggregateId, String partitionKey, String payload, String status, Instant createdAt) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getPartitionKey() { return partitionKey; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }

    public void setStatus(String status) { this.status = status; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
