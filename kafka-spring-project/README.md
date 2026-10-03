# Enterprise Kafka & Spring Boot Playground

A production-grade experimental laboratory implementing the advanced event-driven patterns from the **Kafka Mastery Guide**:
* **KRaft Cluster**: ZooKeeper-less modern Kafka with embedded Raft consensus.
* **Transactional Outbox Pattern**: Solving the Dual-Write problem using PostgreSQL + Spring `@Transactional`.
* **Producer Reliability**: `acks=all`, `enable.idempotence=true`, and partition routing by customer key.
* **Resilience Suite**: `@RetryableTopic` with exponential backoff (1s, 2s, 4s) and `@DltHandler` quarantining.
* **Consumer Idempotency**: DB-backed deduplication table (`processed_events`) with unique constraints.
* **Multiple Consumer Groups**: Demonstrating independent offset progression for `notification-group` and `analytics-group`.

---

## 🏗️ Project Layout

```text
kafka-spring-project/
├── docker-compose.yml              # Kafka KRaft, Kafka UI, PostgreSQL
├── pom.xml                         # Spring Boot 3.3.x, Spring Kafka, Data JPA
├── src/main/java/com/upskill/kafka/
│   ├── KafkaEnterpriseApplication.java
│   ├── config/
│   │   └── KafkaTopicConfig.java   # Auto-creates 3-partition topics on startup
│   ├── event/
│   │   └── OrderCreatedEvent.java  # Immutable Java Record contract
│   ├── order/
│   │   ├── controller/OrderController.java
│   │   ├── entity/OrderEntity.java
│   │   ├── outbox/OutboxRecord.java
│   │   ├── outbox/OutboxRepository.java
│   │   ├── outbox/OutboxPublisher.java   # Scheduled poller dispatching to Kafka
│   │   ├── repository/OrderRepository.java
│   │   └── service/OrderService.java     # Dual-Write prevention
│   ├── notification/
│   │   └── consumer/NotificationConsumer.java # Retry & DLT (concurrency=3)
│   └── analytics/
│       ├── entity/ProcessedEvent.java         # Deduplication table
│       ├── repository/ProcessedEventRepository.java
│       └── consumer/AnalyticsConsumer.java    # Idempotent consumer
└── src/main/resources/
    └── application.yml
```

---

## ⚡ Quick Start

### 1. Launch Infrastructure
From inside the `kafka-spring-project` directory, launch Kafka, Kafka UI, and PostgreSQL:
```bash
docker compose up -d
```

Verify services:
* **Kafka Broker (KRaft)**: `localhost:9092`
* **PostgreSQL Database**: `localhost:5432` (`upskill_db`)
* **Kafka UI Dashboard**: Open [http://localhost:8080](http://localhost:8080) in your browser!

### 2. Start the Spring Boot Application
Run with Maven or launch `KafkaEnterpriseApplication` from your IDE:
```bash
mvn spring-boot:run
```
*(The app runs on port `8081` so it doesn't conflict with Kafka UI on `8080`).*

---

## 🧪 Hands-On Experiments

### Experiment 1: The Transactional Outbox in Action
Send a normal order:
```bash
curl -X POST "http://localhost:8081/api/orders?customerId=CUST-101&amount=249.99"
```

**What happens:**
1. `OrderService` atomically saves `OrderEntity` and `OutboxRecord` in PostgreSQL with status `PENDING`.
2. Within 1 second, `OutboxPublisher` polls the outbox, dispatches the event to `order-events` with partition key `CUST-101`, and marks status `SENT`.
3. Inspect outbox state via REST:
   ```bash
   curl http://localhost:8081/api/orders/outbox
   ```
4. Both `notification-group` and `analytics-group` receive and process the event.

---

### Experiment 2: Partition Key Routing & In-Order Delivery
Send 5 sequential orders for the same VIP customer:
```bash
curl -X POST "http://localhost:8081/api/orders/ordering-test?customerId=VIP-CUSTOMER-99&count=5"
```

**What to Observe:**
* Look at application console logs or open **Kafka UI** -> `order-events` -> **Messages**.
* **Result**: Because all 5 events share the key `VIP-CUSTOMER-99`, Kafka's default murmur2 partitioner routes **all 5 events to the exact same partition** (e.g., Partition 1). In-order processing is strictly guaranteed!

---

### Experiment 3: Poison Pill, Exponential Retries & DLT Quarantine
Trigger an intentional processing error by setting `simulateFailure=true`:
```bash
curl -X POST "http://localhost:8081/api/orders?customerId=CUST-CHAOS&amount=50.00&simulateFailure=true"
```

**What to Observe in Console Logs:**
1. `NotificationConsumer` encounters an exception on `order-events`.
2. **Attempt 1**: Waits 1000ms backoff $\rightarrow$ Retries on `order-events-retry`.
3. **Attempt 2**: Waits 2000ms backoff $\rightarrow$ Retries again.
4. **Attempt 3**: Waits 4000ms backoff $\rightarrow$ Retries again.
5. **Max Retries Exhausted**: The message is routed to `order-events-dlt`.
6. `@DltHandler` intercepts the record:
   ```text
   >>> [DLT QUARANTINE] Order quarantined in DLT! OrderId: ORD-xxxx | Source Topic: order-events-dlt
   ```
7. Notice: **Other incoming orders are NOT blocked!** Non-blocking retry topology keeps the main topic flowing smoothly.

---

### Experiment 4: Idempotent Consumer & Deduplication
Check the PostgreSQL table `processed_events` to see consumer deduplication tracking:
```bash
docker exec -it postgres-db psql -U postgres -d upskill_db -c "SELECT * FROM processed_events;"
```
If network retries redeliver an event with the same `eventId`, `AnalyticsConsumer` catches the duplicate key exception and logs:
```text
⚠️ [AnalyticsConsumer] DUPLICATE EVENT DETECTED! Skipping execution to prevent duplicate calculation.
```

---

## 🔍 Verification & Diagnostics

* **Kafka UI Dashboard**: [http://localhost:8080](http://localhost:8080)
  * View partition distribution across `order-events`, `order-events-retry`, `order-events-dlt`.
  * Inspect consumer group lag for `notification-group` and `analytics-group`.
* **Actuator Health**: [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)
