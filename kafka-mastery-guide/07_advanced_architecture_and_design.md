# Chapter 7: Advanced Architecture, Capacity Planning, and Design Patterns

This chapter covers system architecture, comparing Kafka with other messaging technologies, calculating infrastructure capacity, and reviewing principal engineering design principles.

---

## PART 29: Advanced Architectural Patterns

### 1. Event Sourcing
* **Concept**: Instead of storing only the current state of an entity in a database, store the entire history of state changes as an immutable sequence of events.
* **Database State vs Event Sourcing**:
```text
State Database (Traditional):
Current Balance: $300 (History is lost or archived in audit tables)

Event Store (Event Sourcing):
1. AccountCreated   (Balance: $0)
2. DepositPerformed (+$500)
3. WithdrawExecuted (-$200)
* Current state is derived by replaying events from the beginning.
```
* **When to use**: Financial auditing systems, collaborative document editing, or complex state transitions.
* **When NOT to use**: Simple CRUD applications with high transaction volumes but low auditing requirements.

### 2. CQRS (Command Query Responsibility Segregation)
* **Concept**: Segregating write operations (Commands) from read operations (Queries).
```text
              [Command Request]
                      |
                      v
             +-----------------+
             |   Write model   | ---> Mutates state (PostgreSQL)
             +-----------------+
                      |
           (Outbox Event to Kafka)
                      |
                      v
             +-----------------+
             |   Read model    | <--- Materializes read view (ElasticSearch / Redis)
             +-----------------+
                      |
                      v
               [Query Request]
```
* **When to use**: Applications with asymmetric read/write ratios or complex read query requirements.
* **When NOT to use**: Small systems where a single database can handle both reads and writes efficiently.

### 3. Saga Pattern
The Saga pattern coordinates distributed transactions across multiple microservices using a sequence of local transactions. If one local transaction fails, the Saga runner executes compensating transactions to roll back the changes in reverse order.

```text
Choreography (Decentralized):
OrderService ---> (OrderCreated) ---> PaymentService ---> (PaymentCharged) ---> ShippingService
   * Services communicate reactively by listening to event streams.
   * Simple to implement, but difficult to audit or trace at scale.

Orchestration (Centralized):
OrderService ---> SagaOrchestrator ---> (ChargePayment) ---> PaymentService
                        | <--- (PaymentCharged) -------------|
                        | ---> (ShipPackage) --------------> ShippingService
   * A central coordinator directs the workflow.
   * Clearer status tracking and error handling, but introduces a single point of coordination.
```

---

## PART 30: Detailed Tech Comparisons

| Tech | Delivery Model | Durability | Best For | Trade-off |
| :--- | :--- | :--- | :--- | :--- |
| **RabbitMQ** | Push-based routing | Low (Acks delete data) | Complex routing, low latency | Limited scaling, non-replayable logs |
| **Redis Streams**| Pull-based consumer groups | Volatile (RAM bound) | High-speed cache pipelines | Memory limit constraints |
| **AWS SQS** | Pull-based queue | Transient (Up to 14 days) | Managed queue, low effort | Lacks pub-sub broadcast support |
| **AWS SNS** | Push-based broadcast | Ephemeral (No storage) | Simple notifications, pub-sub | No history retention or replay |
| **gRPC / REST** | Synch Request/Response | None | Immediate query feedback | Tight coupling, cascading failures |
| **Pulsar** | Unified queue/stream | High (Segment storage) | Enterprise multi-tenancy | Highly complex architecture |

---

## PART 31: End-to-End Enterprise Architecture Map

```text
                                CLIENTS (Web / Mobile)
                                          |
                                    Nginx Proxy
                                          |
                                    API Gateway
                                          |
                          +---------------+---------------+
                          |                               |
                   Order Service                   Payment Service
                    (Spring Boot)                   (Spring Boot)
                          |                               |
                     PostgreSQL                      PostgreSQL
                    [outbox table]                  [outbox table]
                          |                               |
                   Debezium CDC Connector          Debezium CDC Connector
                          |                               |
                          +---------------+---------------+
                                          |
                                          v
                              KAFKA DISTRIBUTED CLUSTER
                         [Topics: order-events, payment-events]
                                          |
                +-------------------------+-------------------------+
                |                                                   |
                v                                                   v
       Notification Service                                 Analytics Service
          (Spring Boot)                                       (Spring Boot)
   * Consumer Group: notification-gp                   * Consumer Group: analytics-gp
                |                                                   |
        Sends SMS/Email                                         PostgreSQL
                                                                    |
                                                                    v
                                                         Prometheus & Grafana
                                                         (Monitoring & Alerts)
```

---

## PART 32: Capacity Planning (Numerical Exercise)

### 1. Ingestion Requirements
* **Average traffic**: 10,000 events/sec.
* **Peak traffic**: 50,000 events/sec.
* **Average event payload size**: 2 KB.
* **Retention duration**: 7 days.
* **Replication Factor (RF)**: 3.

### 2. Network Bandwidth Calculation
We must size the network interfaces to support peak traffic rates.

#### Peak Ingress Bandwidth (Data arriving at Leaders):
$$\text{Peak Ingress} = 50,000 \text{ events/sec} \times 2 \text{ KB} = 100,000 \text{ KB/sec} \approx 100 \text{ MB/sec}$$

#### Total Ingress Bandwidth (Leader writes + Replication writes):
Because the Replication Factor is 3, every byte written to the leader must be replicated to 2 follower brokers:
$$\text{Total Ingress} = \text{Peak Ingress} \times \text{RF} = 100 \text{ MB/sec} \times 3 = 300 \text{ MB/sec}$$
Each broker must support a portion of this total throughput. For a 3-broker cluster, each node must handle at least 100 MB/sec of network write traffic.

---

### 3. Disk Storage Calculation
We calculate the storage capacity required to retain 7 days of message logs.

#### Daily Raw Data Volume:
$$\text{Daily Raw Data} = 10,000 \text{ events/sec} \times 2 \text{ KB} \times 86,400 \text{ seconds/day}$$
$$\text{Daily Raw Data} = 1,728,000,000 \text{ KB/day} \approx 1.728 \text{ TB/day}$$

#### Weekly Raw Data Volume (7 days):
$$\text{Weekly Raw Data} = 1.728 \text{ TB/day} \times 7 \text{ days} \approx 12.1 \text{ TB}$$

#### Total Replication Storage Volume:
Multiply the raw data volume by the Replication Factor (3):
$$\text{Total Replicated Storage} = 12.1 \text{ TB} \times 3 = 36.3 \text{ TB}$$

#### Safety Margin & Index Overhead:
Add a 30% buffer to account for disk swap space, active segment indexes, and compaction overhead:
$$\text{Total Disk Allocation} = 36.3 \text{ TB} \times 1.3 \approx 47.2 \text{ TB}$$
Distributed across 3 brokers, each server requires at least **15.8 TB** of persistent block storage.

---

## PART 33: Questions a Principal Engineer Asks Before Approving Kafka

Before deploying Kafka for a new service, review these design questions:

1. **Why Kafka?**
   * *Why it matters*: Verify that the system requires log durability, streaming analytics, or pub-sub patterns. If the team only needs simple point-to-point queues, SQS or RabbitMQ may be simpler to operate.
2. **What is the partitioning key?**
   * *Why it matters*: Choosing a partition key with low cardinality (e.g., country code) creates hot partitions. A unique ID with high cardinality (e.g., `customerId`) ensures even partition distribution.
3. **What happens if Kafka is unavailable?**
   * *Why it matters*: Define the fallback strategy. Will the producers buffer events in memory, return 503 errors to users, or write events to an outbox table?
4. **Is processing idempotent?**
   * *Why it matters*: At-Least-Once delivery guarantees duplicates will occur. The consumer must use a deduplication table or enforce database constraints to prevent duplicate processing.
5. **How will schemas evolve?**
   * *Why it matters*: Establish schema governance early. Registering schemas in a Schema Registry protects downstream consumers from breaking producer updates.

---

## PART 34: Common Kafka Mistakes & Anti-Patterns

* **Anti-Pattern 1: Routing all events to a single partition**
  * *Consequence*: The partition becomes a bottleneck. The consumer group cannot scale past one consumer, and system throughput is limited by the performance of a single thread.
* **Anti-Pattern 2: Allocating too many partitions per broker**
  * *Consequence*: Excess partitions degrade performance. Every partition consumes file descriptors, memory buffers, and CPU cycles. If a broker crashes, recovery times increase significantly as the controller elects leaders for thousands of partitions.
* **Anti-Pattern 3: Treating Kafka as a database**
  * *Consequence*: Kafka is optimized for sequential access, not random-access queries. Relying on Kafka to serve ad-hoc queries leads to slow read speeds and high CPU utilization.
* **Anti-Pattern 4: Storing large payloads in events (e.g., files or PDFs)**
  * *Consequence*: Large payloads bloat memory buffers, saturate network connections, and degrade disk I/O performance. 
  * *Fix*: Use the **Claim Check Pattern**. Upload files to an S3 bucket and publish an event containing the S3 URL.

---

## Quick Revision
* Deriving state by replaying an immutable sequence of events is Event Sourcing.
* Segregating read models from write models to optimize query and write paths is CQRS.
* Peak bandwidth calculations must account for the replication factor (`acks=all` copies writes to followers).
* Size disks to include a 30% safety margin to accommodate compaction overhead and indexing.
* Enforce schema verification and use high-cardinality keys to prevent partition skew.

## Common Mistakes
* **Publishing PDF attachments directly in event payloads**: This causes broker disk cache thrashing. Use S3 references instead.
* **Choosing low-cardinality values as partition keys**: This results in hot partitions that overload individual brokers.
* **Using Kafka transactions to coordinate database writes**: This fails to prevent duplicate database writes if network connections drop.

## Production Perspective
Establish disk alerts at 80% usage. If a broker's storage volume fills completely, Kafka halts processing to protect data logs from corruption. Automate partition balancing (using tools like Cruise Control) to keep resource utilization even across brokers.

## Interview Questions
1. **Explain the Saga Pattern and the difference between choreography and orchestration.** (Choreography relies on reactive event streams between services; orchestration uses a central controller to coordinate steps).
2. **How do you calculate the storage space required for a topic?** (Multiply daily traffic volume by average payload size, retention days, and replication factor, then add a 30% buffer).
3. **What is the Claim Check Pattern?** (Storing large payloads in external storage like S3 and publishing only the reference URL in the event payload).
4. **Why is using a low-cardinality partition key an anti-pattern?** (It causes uneven data distribution, overloading some partitions while leaving others idle).
5. **How does CQRS decouple read and write databases?** (Writes update the primary database, which streams changes via Kafka to update read-optimized views in stores like Elasticsearch).

## Principal Engineer Thinking
When designing event-driven systems, focus on event design. Avoid creating generic, catch-all topics (e.g., `application-logs`). Instead, design scoped, business-aligned topics (e.g., `billing.invoice-events`). Enforce a clear event schema and assign data owners to each topic to ensure schemas are maintained across the organization.
