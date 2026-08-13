# Chapter 4: Spring Boot, Docker-KRaft, and CLI Integration

This chapter covers the hands-on configuration of Kafka. We will construct a local development environment using Docker Compose with KRaft, examine CLI commands, and build a multi-service Spring Boot application.

---

## PART 20: Kafka Docker Setup (KRaft Mode)

### 1. Why KRaft instead of ZooKeeper?
Historically, Kafka relied on Apache ZooKeeper to manage cluster metadata, leader elections, and topic configurations. In modern setups (Kafka 3.x+), **KRaft (Kafka Raft Metadata Mode)** replaces ZooKeeper.
* **Metadata Coordination**: In KRaft, metadata is managed directly within a dedicated quorum of Kafka brokers, avoiding the overhead of maintaining a separate ZooKeeper cluster.
* **Faster Recovery**: If a leader controller fails, KRaft's internal Raft consensus election is significantly faster than ZooKeeper's, reducing recovery times from minutes to seconds.
* **Operational Simplicity**: You only run and monitor one process (Kafka) instead of two.

### 2. The `docker-compose.yml` Configuration
Create the following file in your workspace directory:

```yaml
version: '3.8'

services:
  kafka:
    image: confluentinc/cp-kafka:7.4.0
    container_name: kafka-kraft
    ports:
      - "9092:9092"
      - "29092:29092"
    environment:
      # KRaft Configuration
      KAFKA_NODE_ID: 1
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT'
      KAFKA_ADVERTISED_LISTENERS: 'PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092'
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_PROCESS_ROLES: 'broker,controller'
      KAFKA_CONTROLLER_QUORUM_VOTERS: '1@kafka:29093'
      KAFKA_LISTENERS: 'PLAINTEXT://0.0.0.0:29092,CONTROLLER://0.0.0.0:29093,PLAINTEXT_HOST://0.0.0.0:9092'
      KAFKA_INTER_BROKER_LISTENER_NAME: 'PLAINTEXT'
      KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER'
      KAFKA_LOG_DIRS: '/tmp/kraft-combined-logs'
      # Cluster ID generation (Required for KRaft)
      CLUSTER_ID: 'MkU3OEVBNTcwNTJENDM2Qk'

  kafka-ui:
    image: provectus/kafka-ui:latest
    container_name: kafka-ui
    ports:
      - "8080:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local-kraft
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
    depends_on:
      - kafka

  postgres:
    image: postgres:15-alpine
    container_name: postgres-db
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: upskill_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: password
```

---

## PART 21: Kafka CLI Guide

Once Kafka is running via Docker, you can run diagnostic commands directly from the container's shell.

### 1. Create a Topic
Create a topic named `order-events` with 3 partitions and a replication factor of 1:
```bash
docker exec -it kafka-kraft kafka-topics --bootstrap-server localhost:9092 --create --topic order-events --partitions 3 --replication-factor 1
```

### 2. List Topics
List the available topics in the cluster:
```bash
docker exec -it kafka-kraft kafka-topics --bootstrap-server localhost:9092 --list
```

### 3. Describe a Topic
View partition distribution, leaders, and replicas:
```bash
docker exec -it kafka-kraft kafka-topics --bootstrap-server localhost:9092 --describe --topic order-events
```
*Expected Output:*
```text
Topic: order-events   PartitionCount: 3   ReplicationFactor: 1
  Topic: order-events   Partition: 0   Leader: 1   Replicas: 1   Isr: 1
  Topic: order-events   Partition: 1   Leader: 1   Replicas: 1   Isr: 1
  Topic: order-events   Partition: 2   Leader: 1   Replicas: 1   Isr: 1
```

### 4. Produce Events (CLI Console)
Start a console producer to publish raw text messages:
```bash
docker exec -it kafka-kraft kafka-console-producer --bootstrap-server localhost:9092 --topic order-events --property parse.key=true --property key.separator=:
```
*Input:*
```text
key1:{"orderId":"1001","status":"CREATED"}
key2:{"orderId":"1002","status":"PAID"}
```

### 5. Consume Events (CLI Console)
Read events from the beginning of the topic:
```bash
docker exec -it kafka-kraft kafka-console-consumer --bootstrap-server localhost:9092 --topic order-events --from-beginning --property print.key=true
```

### 6. Inspect Consumer Groups
List the active consumer groups:
```bash
docker exec -it kafka-kraft kafka-consumer-groups --bootstrap-server localhost:9092 --list
```

View lag, partition assignments, and offset details for a consumer group:
```bash
docker exec -it kafka-kraft kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group notification-service
```
*Expected Output:*
```text
GROUP                 TOPIC            PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
notification-service  order-events     0          105             110             5               client-1        /127.0.0.1      client-1
notification-service  order-events     1          92              92              0               client-2        /127.0.0.1      client-2
notification-service  order-events     2          114             120             6               client-1        /127.0.0.1      client-1
```

---

## PART 18 & 19: Spring Boot Practical Project

This project simulates an e-commerce ecosystem consisting of:
1. `order-service` (Produces order events to Kafka)
2. `notification-service` (Consumes events in its own consumer group to notify users)
3. `analytics-service` (Consumes events in its own consumer group to track order stats)

```text
                     order-service
                           |
            (Publishes OrderCreatedEvent)
                           v
                     Kafka Topic: order-events
                           |
             +-------------+-------------+
             |                           |
             v                           v
     notification-service        analytics-service
     (Group: notification-gp)    (Group: analytics-gp)
```

### 1. The Domain Model (Record Class)
```java
package com.upskill.kafka.event;

import java.math.BigDecimal;

public record OrderCreatedEvent(
    String eventId,
    String orderId,
    String customerId,
    BigDecimal amount,
    String timestamp
) {}
```

### 2. Order Service: The Producer Component
```java
package com.upskill.kafka.producer;

import com.upskill.kafka.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);
    private static final String TOPIC = "order-events";
    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    public OrderEventProducer(KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreatedEvent: {}", event.orderId());
        
        // Construct Kafka Message with Headers and Partition Key
        Message<OrderCreatedEvent> message = MessageBuilder
                .withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, TOPIC)
                .setHeader(KafkaHeaders.KEY, event.customerId()) // Route by Customer ID to preserve ordering
                .setHeader("correlation-id", UUID.randomUUID().toString())
                .build();

        kafkaTemplate.send(message)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Successfully sent order: {} to partition: {} with offset: {}", 
                                event.orderId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to send order event: {}", event.orderId(), ex);
                    }
                });
    }
}
```

### 3. Notification Service: Consumer Group 1
```java
package com.upskill.kafka.consumer;

import com.upskill.kafka.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

@Service
public class NotificationServiceConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceConsumer.class);

    @KafkaListener(
            topics = "order-events",
            groupId = "notification-group",
            concurrency = "3" // Spawns 3 consumer threads to map to the 3 partitions
    )
    public void consume(OrderCreatedEvent event, 
                        @Header("correlation-id") String correlationId,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
        
        log.info("Notification Service - Processing order: {} in partition: {} | Correlation: {}", 
                event.orderId(), partition, correlationId);
        
        // Execute email/SMS notification logic here
    }
}
```

### 4. Analytics Service: Consumer Group 2
```java
package com.upskill.kafka.consumer;

import com.upskill.kafka.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsServiceConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsServiceConsumer.class);

    @KafkaListener(
            topics = "order-events",
            groupId = "analytics-group"
    )
    public void consume(OrderCreatedEvent event) {
        log.info("Analytics Service - Recorded transaction for customer: {} worth: {}", 
                event.customerId(), event.amount());
        
        // Update dashboard cache / data warehouse here
    }
}
```

### 5. `application.yml` Properties Configuration
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        enable.idempotence: true
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.upskill.kafka.event"
        spring.json.value.default.type: "com.upskill.kafka.event.OrderCreatedEvent"
      auto-offset-reset: earliest
```

---

## Quick Revision
* KRaft mode eliminates ZooKeeper dependency and accelerates recovery from controller failures.
* A Docker Compose setup with `cp-kafka` requires configuring matching listener interfaces: Internal (`PLAINTEXT`) and Host-facing (`PLAINTEXT_HOST`).
* Use `kafka-topics --describe` to inspect partitions, replica layout, and ISR membership.
* Multiple Consumer Groups poll the same topic concurrently. Each group reads every event and maintains independent offsets.
* The `concurrency` property in Spring's `@KafkaListener` spawns multiple consumer threads to read from assigned partitions.

## Common Mistakes
* **Using mismatching ports for advertised listeners in Docker**: Setting up internal container networks without matching external host definitions blocks application connections.
* **Shared Consumer Groups**: Configuring the same `groupId` for two different services causes them to divide events between themselves, rather than both services receiving all events.

## Production Perspective
Do not run Kafka inside Kubernetes or Docker containers in production without persistent storage volumes. If a broker pod restarts, it must be able to read its local log segment directory on disk to verify its offset position.

## Interview Questions
1. **Explain the difference between ZooKeeper mode and KRaft mode.** (ZooKeeper uses an external synchronization cluster; KRaft uses internal Raft consensus within the Kafka brokers).
2. **How does Spring Boot resolve the target class type when consuming JSON payloads?** (Via JSON headers or by specifying the default type configuration in properties: `spring.json.value.default.type`).
3. **What is the significance of the `concurrency` property in `@KafkaListener`?** (It defines the number of concurrent consumer threads spawned in the listener container to read from topic partitions).
4. **How do you publish message headers using Spring Kafka?** (By wrapping the payload in a `Message` object using `MessageBuilder` and adding headers, which are mapped to Kafka record headers).
5. **How can you inspect if partitions are balanced across your consumer group instances?** (Run `kafka-consumer-groups --describe` to check the assignment mapping).

## Principal Engineer Thinking
When building event-driven systems, ensure your topic provisioning process is automated using infrastructure-as-code (e.g., Terraform or GitOps pipelines). Avoid enabling `auto.create.topics.enable=true` in production, as typos in application code can result in the automatic creation of topics with single partitions and default settings, leading to ingestion bottlenecks.
