package com.upskill.kafka.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEvent() {}

    public ProcessedEvent(String eventId, String consumerGroup, Instant processedAt) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.processedAt = processedAt;
    }

    public String getEventId() { return eventId; }
    public String getConsumerGroup() { return consumerGroup; }
    public Instant getProcessedAt() { return processedAt; }
}
