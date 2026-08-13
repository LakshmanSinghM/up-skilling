# Chapter 10: Enterprise System Design Exercise

This chapter presents a system design challenge for a high-scale order processing platform and provides a reference solution.

---

## PART 42: The System Design Problem

### Case Study: High-Scale Order Processing Platform
You are the Principal Engineer tasked with designing a new order processing system. The business requirements are as follows:

* **Daily Volume**: 10 million orders processed per day.
* **Peak Volume**: The system must handle spikes of up to 50,000 orders/second.
* **Average Payload Size**: 1.5 KB per order event.
* **Downstream Services**:
  1. `payment-service` (Charges cards; cannot miss events).
  2. `inventory-service` (Updates stock levels).
  3. `notification-service` (Sends SMS/email alerts).
  4. `analytics-service` (Feeds business BI dashboards).
* **Retention Window**: 7 days.
* **Availability & Reliability**: No lost payment events. Zero-downtime tolerance for broker failures. Safe, duplicate-resilient (idempotent) execution.

---

### Design Requirements:
Provide a system design specifying:
1. **Topic Design**: Names, partitions, and keys.
2. **Cluster Topology**: Broker count, replication factors, and `min.insync.replicas`.
3. **Capacity Math**: Disk space, network bandwidth, and JVM tuning.
4. **Client Configurations**: Producer and consumer settings for durability and performance.
5. **Deduplication Strategy**: Enforcing idempotency across services.
6. **Outbox & Database Integration**: Handling database updates and event publishing.
7. **Disaster Recovery (DR)**: Multi-region failover strategy.
8. **Monitoring Setup**: Key metrics and alert thresholds.

---

## Principal Engineer Reference Solution

### 1. Topic Topology Design

We will create a single event-stream topic: `order-events`.

```text
                                TOPIC: order-events
  +-----------------------------------------------------------------------------+
  | Partition 0 | Partition 1 | Partition 2 | ... | Partition 31 (Total: 32)     |
  +-----------------------------------------------------------------------------+
         |             |             |                  |
         v             v             v                  v
     Mapped by: MurmurHash2(customerId) % 32
     (Guarantees sequential order per customer, even partition distribution)
```

* **Topic Name**: `orders.order-events`
* **Partition Count**: 32 partitions.
  * *Reasoning*: A single consumer thread can process approximately 2,000 events/sec (including database writes). 
    To support a peak throughput of 50,000 events/sec, we calculate:
    $$\text{Minimum Partitions} = \frac{50,000 \text{ events/sec}}{2,000 \text{ events/sec/thread}} = 25 \text{ partitions}$$
    We round up to 32 partitions to align with binary boundaries and allow a buffer for future scale.
* **Partition Key**: `customerId`.
  * *Reasoning*: Using `customerId` groups a customer's order events (Created, Updated, Cancelled) on the same partition, guaranteeing they are processed in order. Using `orderId` would also distribute writes evenly, but would scatter events for the same customer across partitions, preventing order history tracing.

---

### 2. Capacity & Cluster Sizing Calculations

#### Ingestion Bandwidth:
$$\text{Peak Ingress} = 50,000 \text{ events/sec} \times 1.5 \text{ KB} = 75,000 \text{ KB/sec} \approx 75 \text{ MB/sec}$$
$$\text{Total Ingress (with RF=3)} = 75 \text{ MB/sec} \times 3 = 225 \text{ MB/sec}$$

#### Storage Requirements:
$$\text{Daily Volume} = 10,000,000 \text{ events} \times 1.5 \text{ KB} = 15 \text{ GB/day}$$
$$\text{Weekly Volume (7 days)} = 15 \text{ GB/day} \times 7 = 105 \text{ GB}$$
$$\text{Total Replicated Volume (RF=3)} = 105 \text{ GB} \times 3 = 315 \text{ GB}$$
$$\text{Total Storage with 30% safety margin} = 315 \text{ GB} \times 1.3 \approx 410 \text{ GB}$$

#### Cluster Node Count:
* We deploy a **5-node KRaft broker cluster**.
* *Reasoning*: Spreading 32 partitions and their replicas across 5 nodes provides a balanced distribution (approximately 19 partitions per broker). This configuration also ensures the cluster can tolerate the loss of up to 2 brokers simultaneously without violating availability or partition replicas constraints.
* Each broker requires:
  * Network card capacity: At least 10 Gbps (supporting replication and egress traffic).
  * Storage: 100 GB of high-speed EBS gp3 SSD volume.
  * JVM Heap: 8 GB (with G1GC enabled), leaving remaining RAM for the OS Page Cache.

---

### 3. Client & Reliability Configuration

#### Producer configuration (Order Service):
```properties
acks=all
enable.idempotence=true
retries=2147483647
max.in.flight.requests.per.connection=5
compression.type=zstd
linger.ms=20
batch.size=65536
```
* *Reasoning*: Setting `acks=all` combined with `enable.idempotence=true` prevents data loss and duplicate writes. Using `zstd` compression with a `linger.ms=20` window maximizes batching efficiency, reducing network overhead under peak loads.

#### Topic Configuration:
```properties
replication.factor=3
min.insync.replicas=2
cleanup.policy=delete
retention.ms=604800000
```
* *Reasoning*: Enforcing `min.insync.replicas=2` ensures that if a leader broker fails, at least one follower broker has replicated the write before the client receives success.

#### Consumer Configuration (Downstream Services):
* Each service is deployed under a separate consumer group:
  * `payment-service` $\rightarrow$ group `group-payment`
  * `inventory-service` $\rightarrow$ group `group-inventory`
  * `notification-service` $\rightarrow$ group `group-notification`
  * `analytics-service` $\rightarrow$ group `group-analytics`
* Properties:
```properties
isolation.level=read_committed
enable.auto.commit=false
max.poll.interval.ms=300000
max.poll.records=500
```
* *Reasoning*: Disabling auto-commits ensures offsets are committed only after the database write succeeds. Setting `isolation.level=read_committed` filters out aborted transactional messages.

---

### 4. Resiliency & Deduplication Architecture

```text
                           CLIENT / PRODUCER
                                   |
                         (Publishes Order Event)
                                   v
                             KAFKA CLUSTER
                                   |
                                   v
                          PAYMENT SERVICE (Consumer)
                                   |
                  (1) Starts Local DB Transaction
                                   |
                                   v
                     PostgreSQL (UPSKILL_DB)
       +---------------------------------------------------+
       | (2) Insert Event ID to Deduplication Table        |
       |     * If duplicate: Throw Unique Constraint Err   |
       |     * If success: Proceed                         |
       +---------------------------------------------------+
                                   |
                                   v
       +---------------------------------------------------+
       | (3) Insert Invoice Record to Payments Table       |
       +---------------------------------------------------+
                                   |
                  (4) Commits Local DB Transaction
                                   |
                   (5) Commits Kafka Offset (Success)
```

* **Idempotency Strategy**: The payment service uses client-side deduplication. We create a `processed_events` table in PostgreSQL with a unique constraint on `event_id`.
* **Execution Flow**:
  1. The consumer polls a batch of events.
  2. Inside a database transaction, it attempts to insert the event's unique ID into `processed_events`.
  3. If the insert throws a unique constraint exception, the event is skipped as a duplicate.
  4. If the insert succeeds, the service writes the payment invoice record and commits the database transaction.
  5. The consumer then commits the offset to Kafka.

---

### 5. Integration: Outbox Pattern via Debezium CDC

To prevent dual-write state inconsistencies, the `Order Service` uses the Outbox Pattern:

```text
Order API Request -> Starts DB Transaction -> 
  - Writes Order Record to 'orders' Table
  - Writes Outbox Event Record to 'outbox' Table
DB Transaction Commits Atomically.

             PostgreSQL WAL (Write-Ahead Log)
                           |
                           v
                Debezium Connector Pod
                           |
                (Streams Outbox Events)
                           v
                     Kafka Topic
```

* **Database Engine**: PostgreSQL.
* **Event Capturing**: Debezium tail-reads the PostgreSQL Write-Ahead Log (WAL) to extract records written to the `outbox` table. It streams these records to the `orders.order-events` topic in Kafka, and then deletes the processed outbox table records asynchronously.
* *Reasoning*: This ensures database updates and event publishing succeed or fail together, resolving the dual-write consistency problem.

---

### 6. Disaster Recovery (DR)

We use an **Active-Passive Multi-Region Topology**:

```text
       PRIMARY REGION (us-east-1)                 SECONDARY REGION (us-west-2)
  +----------------------------------+       +----------------------------------+
  | Kafka Cluster (Active)           |       | Kafka Cluster (Passive / Standby)|
  | [Topic: orders.order-events]     |       | [Topic: orders.order-events]     |
  +----------------------------------+       +----------------------------------+
                   \                                         ^
                    \--- MirrorMaker 2 (Cross-Region replication) --/
```

* **Replication Tool**: **MirrorMaker 2** or **Confluent Cluster Linking** replicates topic logs and consumer offset positions asynchronously from the primary region (`us-east-1`) to the disaster recovery region (`us-west-2`).
* **Failover Protocol**:
  1. If the primary region fails, DNS routing switches client traffic to the secondary region.
  2. Consumer groups in the secondary region fetch their replicated offsets from the MirrorMaker metadata mapping and resume processing.
  3. Producers switch their bootstrap server configurations to the secondary cluster.

---

### 7. Production Monitoring & Alerts

We configure Prometheus to scrape JMX metrics and display them on a Grafana dashboard.

#### Alert Rules:
* **Alert: Offline Partitions Count**
  * *Condition*: `OfflinePartitionsCount > 0`
  * *Priority*: Critical (P0 - Page Oncall).
  * *Reasoning*: Indicates partitions are unavailable for writes and reads, indicating a cluster outage.
* **Alert: Under-Replicated Partitions**
  * *Condition*: `UnderReplicatedPartitions > 0` for $> 5$ minutes.
  * *Priority*: Warning (P1 - Slack alert).
  * *Reasoning*: Indicates a broker replication sync failure, increasing data loss risks if the leader fails.
* **Alert: Consumer Group Lag Spike**
  * *Condition*: `RecordsLag > 200,000` events or consumer lag grows continuously for 15 minutes.
  * *Priority*: Warning (P1).
  * *Reasoning*: Indicates downstream processing bottlenecks or consumer instances offline.

---

## Quick Revision
* Sizing partition counts depends on target ingestion rates and single-thread consumer processing speeds.
* Enforce `acks=all`, `enable.idempotence=true`, and `min.insync.replicas=2` to prevent data loss.
* Kafka transactions do not coordinate writes to external databases. Use deduplication tables inside local database transactions to enforce idempotency.
* The Outbox pattern paired with CDC (Debezium) resolves the dual-write problem by updating databases and publishing events atomically.
* Set alert paging thresholds on `OfflinePartitionsCount` and `UnderReplicatedPartitions` to catch broker failures early.

## Common Mistakes
* **Spanning a single Kafka cluster across geographic regions**: This introduces WAN network latency bottlenecks, degrading write performance.
* **Under-provisioning partitions**: Sizing partition counts too low prevents consumer groups from scaling to handle ingestion spikes.
* **Configuring auto-commit offset strategies with payment transactions**: This risk committing offsets before processing completes, leading to data loss if consumers crash.
