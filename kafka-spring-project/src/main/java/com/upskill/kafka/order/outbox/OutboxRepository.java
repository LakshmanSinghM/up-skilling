package com.upskill.kafka.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxRecord, Long> {
    List<OutboxRecord> findTop20ByStatusOrderByCreatedAtAsc(String status);
    Optional<OutboxRecord> findByEventId(String eventId);
}
