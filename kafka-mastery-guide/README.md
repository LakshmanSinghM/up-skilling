# Kafka Mastery: Principal Engineer Production Guide

Welcome to the **Kafka Mastery & Distributed Systems Architecture Guide**. This handbook is designed as a long-term learning, system design, and production-troubleshooting reference. It is structured to take you from a complete beginner to a Principal Engineer-level understanding of Apache Kafka, mapping concepts back to your existing technology stack (Java, Spring Boot, PostgreSQL, Redis, Docker, Kubernetes, AWS, Prometheus, Grafana).

---

## Table of Contents

* [01. Foundations: Clusters, Topics, Partitions, Brokers, and Replication](./01_foundations.md)
  * PART 1: Kafka Fundamentals (Why Kafka, RabbitMQ vs Redis Streams vs Kafka, Message vs Event)
  * PART 2: Topics Deep Dive (Log segments, Retention Policies, Compaction)
  * PART 3: Partitions Deep Dive (Hashing, Keys, Hot Partitions, Throughput)
  * PART 4: Brokers Deep Dive (Metadata, Storage, Broker Networking)
  * PART 5: Topic-Partition-Broker Relationship (Physical vs Logical mapping)
  * PART 6: Replication & Durability (Leader/Follower, ISR, Leader Election, Lag)
* [02. Producers, Consumers, and Consumer Groups](./02_producers_consumers.md)
  * PART 7: Producers Deep Dive (`KafkaProducer`, Serialization, Batching, `acks=0/1/all`, Idempotency)
  * PART 8: Consumers Deep Dive (`poll()` Loop internals, Manual vs Auto Commits)
  * PART 9: Consumer Groups (Parallel processing, Broadcast vs Queue behavior)
  * PART 10: Consumer Group Scaling (Partition-to-Consumer ratios)
  * PART 11: Consumer Rebalancing (Static membership, Cooperative sticky, Rebalance storms)
  * PART 12: Offset Management (Committed offsets, Current Position, Reset policies)
  * PART 13: Consumer Lag (Calculation, Alerts, Mitigating lag in production)
* [03. Reliability, Idempotency, and Enterprise Integration](./03_reliability_integration.md)
  * PART 14: Delivery Semantics (At-least-once, At-most-once, Exactly-once timelines)
  * PART 15: Idempotency (Deduplication, Idempotency keys, Database constraints)
  * PART 16: Resiliency (Retry topics, Exponential backoff, Dead Letter Topics (DLT))
  * PART 17: Kafka + PostgreSQL Integration (Dual-write problem, Outbox Pattern, CDC, Debezium)
* [04. Spring Boot & Docker-KRaft Implementation](./04_spring_and_docker_projects.md)
  * PART 18: Complete Spring Boot Enterprise Codebase (Order, Notification, Analytics)
  * PART 19: Multiple Consumer Groups Practical Project (Code and validation commands)
  * PART 20: Kafka Docker Setup (Modern KRaft architecture, Kafka UI, PostgreSQL)
  * PART 21: Kafka CLI Guide (Production commands for creation, description, and diagnostics)
* [05. Production Operations, Failure Scenarios, and Infrastructure](./05_production_operations.md)
  * PART 22: Failure Playbooks (10 real-world incident walkthroughs: logs, metrics, fixes)
  * PART 23: Why Brokers Fail (Resource exhaustion, file descriptors, GC pauses checklist)
  * PART 24: Monitoring System (Prometheus metrics, Grafana dashboards, Loki logs)
  * PART 25: Kubernetes Deployment (StatefulSets, PVs, Disruption budgets, Managed vs Self-hosted)
* [06. Security, Schema Registry, and Transactions](./06_security_schema_transactions.md)
  * PART 26: Security (TLS encryption, SASL auth, ACL configurations, Topic permissions)
  * PART 27: Schema Management (Schema Registry, Avro/Protobuf, Backward/Forward compatibility)
  * PART 28: Transactions (Transactional.id, Two-phase commit limits, read_committed)
* [07. Advanced Architecture, Capacity Planning, and Design Patterns](./07_advanced_architecture_and_design.md)
  * PART 29: Advanced Design Patterns (CQRS, Event Sourcing, Saga Pattern orchestration)
  * PART 30: Detailed Tech Comparisons (RabbitMQ, Redis, SQS, SNS, WebSockets, gRPC, Pulsar)
  * PART 31: End-to-End Enterprise Architecture Map
  * PART 32: Capacity Planning & Numerical Exercise (Calculating CPU, Disk, Network for 50k events/sec)
  * PART 33: Questions a Principal Engineer Asks Before Approving Kafka
  * PART 34: Common Kafka Mistakes & Anti-Patterns
* [08. Comprehensive Kafka Interview Preparation](./08_interview_prep.md)
  * PART 35: 150+ Interview Questions with Production Answers (Beginner, Intermediate, Advanced, PE)
* [09. Step-by-Step Hands-On Labs](./09_hands_on_labs.md)
  * PART 36: Labs 1 to 20 (Local setups, lag simulation, rebalance testing, Outbox pattern)
* [10. Enterprise System Design Exercise](./10_system_design_exercise.md)
  * PART 42: Design a 10M orders/day, 50k orders/sec high-scale platform + PE Reference Solution

---

## Kafka Mental Model

To understand Kafka, you must shift your mindset from **queues** (where messages are deleted once read) to a **distributed append-only commit log** (where events are stored sequentially and can be read repeatedly by different systems).

```text
                                PHYSICAL BROKER CLUSTER
               +-------------------------------------------------------+
               |  Broker 1 (Leader P0)       |  Broker 2 (Leader P1)   |
               |  [Segment 001.log]          |  [Segment 001.log]      |
               |  Offset 0: Event A          |  Offset 0: Event C      |
               |  Offset 1: Event B  <--[P0] |  Offset 1: Event D <--[P1]
               +-----------------------------+-------------------------+
                                             ^
                                             | Reads sequentially without deleting
                        +--------------------+--------------------+
                        |                                         |
            Consumer Group A: Billing                 Consumer Group B: Shipping
        +-------------------------------+         +-------------------------------+
        |  Consumer C1 (Reads P0)       |         |  Consumer C3 (Reads P0)       |
        |  Current Position: Offset 2   |         |  Current Position: Offset 1   |
        +-------------------------------+         +-------------------------------+
        |  Consumer C2 (Reads P1)       |         |  Consumer C4 (Reads P1)       |
        |  Current Position: Offset 1   |         |  Current Position: Offset 2   |
        +-------------------------------+         +-------------------------------+
```

---

## Kafka in 1 Minute

* **What is it?** Kafka is a distributed, horizontally scalable, crash-tolerant append-only commit log.
* **Storage**: Data is stored as an immutable sequence of bytes on disk (not in memory). It is NOT deleted when read.
* **Topics & Partitions**: A **Topic** is a logical stream. A **Partition** is the physical unit of scalability and ordering. Events with the same partition key land on the same partition and preserve strict write-order.
* **Producers & Consumers**: **Producers** choose where to write (by hashing the key). **Consumers** pull data at their own pace using a `poll()` loop.
* **Consumer Groups**: A cluster of consumers sharing partitions to distribute work. Each consumer group maintains its own position (**Offsets**) independently.
* **Replication**: Partitions are replicated across multiple brokers (ISR). If a leader broker dies, a follower is promoted automatically.

---

## Kafka in 10 Minutes

### 1. The Core Architecture Shift
In traditional architectures, service communication is **synchronous and point-to-point** (REST APIs). If Service B is down, Service A fails or blocks. In an Event-Driven Architecture (EDA) powered by Kafka:
* **Producers** publish events to Kafka. They do not know or care who consumes them.
* **Kafka** persists these events to disk, replicating them for fault tolerance.
* **Consumers** pull events asynchronously. If Service B goes down, events accumulate in Kafka. When Service B recovers, it resumes reading from its last recorded **committed offset**.

### 2. Physical Layout vs Logical Layout
* **Topic**: A logical bucket (e.g., `order-events`).
* **Partition**: The physical file on the broker's disk. A topic is split into $N$ partitions. Each partition is an ordered, immutable log.
* **Broker**: A single server running Kafka. A cluster contains multiple brokers.
* **Distribution**: Kafka spreads partitions of a single topic across all available brokers. This allows writes and reads to be distributed across multiple physical machines, removing CPU, memory, and network bottlenecks.

### 3. Understanding the Offsets and Log
Every event in a partition gets a sequential, unique, 64-bit integer called an **offset**. 
* The offset represents the logical timestamp / position of the event within that specific partition.
* Kafka does not track what has been read by deleting data. Instead, it tracks the **Committed Offset** for each Consumer Group in an internal topic: `__consumer_offsets`.
* This enables **replayability**: if your database gets corrupted, you can reset your consumer group offset back to 0 and re-process the last 7 days of events.

### 4. High Availability (HA) via Replication
Every partition has 1 **Leader** broker and $N-1$ **Follower** brokers.
* All write and read requests go to the **Leader** broker (under default configuration).
* Followers act as passive consumers, replicating data to their own disk.
* **In-Sync Replicas (ISR)**: The set of followers that are actively keeping up with the leader. If the leader crashes, the controller elects one of the ISR followers as the new leader.
* **Acks Configuration**:
  * `acks=0`: Producer fire-and-forget (high throughput, high loss risk).
  * `acks=1`: Producer waits for Leader to write to local log (medium risk).
  * `acks=all` (or `-1`): Producer waits for all ISRs to write to their logs (maximum durability).

---

## Kafka Cheat Sheet

| Term | Analogy | Description |
| :--- | :--- | :--- |
| **Broker** | Warehouse | A single physical or virtual machine running the Kafka process. |
| **Topic** | Department Category | A logical stream of events (e.g., `payment-transactions`). |
| **Partition** | Loading Dock Lane | A physical subdirectory on a broker's disk. The unit of parallelism. |
| **Offset** | Ticket Number | A sequential, unique ID assigned to each record inside a partition. |
| **Producer** | Delivery Truck | A client application that publishes events to Kafka topics. |
| **Consumer** | Processing Worker | A client application that polls records from Kafka. |
| **Consumer Group** | Department Team | A cooperative group of consumers working together to read a topic. |
| **Leader** | Team Captain | The primary broker handling all reads and writes for a partition. |
| **Follower** | Shadow/Backup | A broker replicating the leader's data for fault tolerance. |
| **ISR (In-Sync Replica)** | Up-to-date Assistant | Followers that are successfully keeping up with the leader's log. |
| **Replay** | Rewinding Tape | Resetting consumer offsets to re-process historical events. |
| **Rebalance** | Redistributing Work | The process of reassigning partitions when consumers join or leave. |
| **Lag** | Unread Backlog | The difference between the latest produced offset and the consumer's current offset. |
| **Compaction** | Database Upsert | Cleanup policy retaining only the latest value for each key in a partition. |
| **DLT (Dead Letter Topic)** | Quarantine Bin | A topic where poison/unprocessible events are routed for manual inspection. |
| **Outbox Pattern** | Outbox Table | Storing state and event in the same DB transaction, then publishing asynchronously. |
| **Schema Registry** | Contract Library | External service enforcing schema compatibility rules (e.g., Avro, Protobuf). |
| **KRaft** | Self-managed Metadata | Kafka Raft Metadata mode, replacing the ZooKeeper dependency. |
