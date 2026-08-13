# Chapter 2: Producers, Consumers, and Consumer Groups

This chapter covers client-side mechanics. We will analyze the internal batching of the producer, the polling model of the consumer, partition assignment strategies, and consumer group scaling.

---

## PART 7: Producers Deep Dive

### 1. Simple Definition
A **Producer** is a client application that sends records to one or more Kafka topics.

### 2. Real-World Analogy: Mailroom Clerk
Imagine a company mailroom clerk. Instead of running to the post office every time a letter is written, the clerk places letters in bins sorted by ZIP code (Partitions). When a bin fills up or 10 minutes pass, the clerk packs the letters into a truck and ships them to the distribution center.

### 3. Technical Explanation: Producer Internals
When you invoke `producer.send(record)`, the message does not immediately go to the network. It passes through an internal pipeline:

```text
+------------------+
|  producer.send() |
+------------------+
         |
         v
+------------------+     +-----------------------+
|    Serializer    | --> | Convert Key/Value to  |
+------------------+     | byte arrays (JSON/etc)|
         |               +-----------------------+
         v
+------------------+     +-----------------------+
|    Partitioner   | --> | Determine destination |
+------------------+     | Partition Index       |
         |               +-----------------------+
         v
+------------------+     +-----------------------------------+
|  Record Accumulator|-->| Buffers records in memory pools  |
+------------------+     | partitioned by Topic-Partition    |
         |               +-----------------------------------+
         v
+------------------+     +------------------------------------+
|  Sender Thread   | --> | Pulls batches from Accumulator and |
+------------------+     | sends socket write requests to Kafka|
                         +------------------------------------+
```

#### Key Producer Configurations:
* **`batch.size`**: The memory threshold (in bytes) to accumulate records before sending them to the broker.
* **`linger.ms`**: The time to wait for more records to arrive to fill a batch. If the limit is reached before `batch.size` is full, the batch is sent anyway.
* **`compression.type`**: Compression (e.g., `lz4`, `snappy`, `zstd`) compresses batches in memory, reducing network payloads and disk usage on the broker.
* **`acks` Configuration**:
  * `acks=0`: Fire-and-forget. The client doesn't wait for any broker acknowledgement. Maximum throughput, high risk of data loss.
  * `acks=1`: The client waits for the partition Leader to write the record to its local log.
  * `acks=all` (or `-1`): The client waits for the Leader and all In-Sync Replicas (ISRs) to acknowledge the write. Essential for financial and core system events.

#### Idempotent Producer
By setting `enable.idempotence=true`, Kafka prevents duplicate writes caused by network retries.
* **How it works**: The broker assigns a unique Producer ID (PID) and a monotonically increasing Sequence Number to every batch. If a broker receives a sequence number it has already written, it discards the duplicate write and returns an acknowledgement to the producer.

```text
Producer                                                   Broker
   | ----- PID: 105, Seq: 1 (OrderCreated) ---------------> | (Writes to log)
   | <---- ACK (Lost due to network drop) ----------------- | 
   | (Retries due to no ACK)                                |
   | ----- PID: 105, Seq: 1 (OrderCreated) ---------------> | (Detects duplicate, discards,
   | <---- ACK (Returned successfully) -------------------- |  does not write again)
```

### 4. Spring Boot Example: Enterprise Producer Configuration
```java
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, OrderCreatedEvent> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        
        // Resilience settings
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        configProps.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5); // Safe for idempotency
        
        // Performance tuning
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 20); // Wait up to 20ms for batching
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024); // 32KB batch size
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

---

## PART 8: Consumers Deep Dive

### 1. Simple Definition
A **Consumer** is a client application that polls records from one or more Kafka topics.

### 2. Real-World Analogy: Conveyor Belt Sorter
Imagine a worker sitting at a conveyor belt (Partition). The worker does not get pushed items; instead, when they are ready, they reach out and grab a tray of items (batch poll) from the belt, process them, and then mark their position on the belt.

### 3. Technical Explanation: The Polling Loop
Kafka consumers operate on a **pull-based** model. The consumer must continuously call the `poll()` method in a loop.
* **Does Kafka consumer poll internally?** Yes. The `poll(Duration timeout)` method is a blocking call that fetches data from the broker. Under the hood, it coordinates group membership, heartbeats, partition rebalances, and reads data into local buffers.

```java
// What is happening inside the Spring @KafkaListener container:
try (KafkaConsumer<String, OrderEvent> consumer = new KafkaConsumer<>(configs)) {
    consumer.subscribe(Collections.singletonList("order-events"));
    
    while (running) {
        // Blocks until records are fetched or timeout occurs
        ConsumerRecords<String, OrderEvent> records = consumer.poll(Duration.ofMillis(100));
        
        for (ConsumerRecord<String, OrderEvent> record : records) {
            try {
                processEvent(record.value());
            } catch (Exception e) {
                handleProcessingError(record, e);
            }
        }
        // Commit offsets to broker
        consumer.commitSync();
    }
}
```

#### Commit Strategies
1. **Auto Commit (`enable.auto.commit=true`)**: The consumer automatically commits the highest offset returned by `poll()` at intervals defined by `auto.commit.interval.ms`.
   * *Risk*: If your consumer polls a batch, and crashes halfway through processing, the committed offset has already progressed. The unprocessed events in that batch are lost to that consumer group.
2. **Manual Sync Commit (`commitSync()`)**: Blocks the execution thread until the broker responds to the offset commit request. Relies on retries if write fails.
3. **Manual Async Commit (`commitAsync()`)**: Fires commit requests asynchronously without blocking. Faster, but ordering must be managed to prevent older offsets overwriting newer ones on retry.

---

## PART 9: Consumer Groups

### 1. Simple Definition
A **Consumer Group** is a set of consumers that cooperate to read from a topic. Each consumer within the group is assigned a subset of the topic's partitions.

### 2. Real-World Analogy: Collaborative Department Teams
Suppose you have a topic `order-events` with 3 partitions.
* **Consumer Group A (Billing)**: Team of 3 clerks. Clerk 1 reads Partition 0, Clerk 2 reads Partition 1, Clerk 3 reads Partition 2. They divide the work to process invoices faster.
* **Consumer Group B (Analytics)**: An entirely separate department. They also have 3 workers who read the exact same events to update dashboards.
* **Both groups operate independently.** Group A processing a message has no effect on Group B's capacity to read it.

```text
                          TOPIC: order-events
            +--------------------------------------------+
            |  Partition 0   |  Partition 1   |  Partition 2   |
            +--------------------------------------------+
                 /                |                \
   Group A (Billing)        Group B (Analytics)     Group C (Shipping)
   +---------------+        +---------------+       +---------------+
   | C1 -> Part 0  |        | C4 -> Part 0  |       | C7 -> Part 0  |
   | C2 -> Part 1  |        | C5 -> Part 1  |       | C8 -> Part 1  |
   | C3 -> Part 2  |        | C6 -> Part 2  |       | C9 -> Part 2  |
   +---------------+        +---------------+       +---------------+
```

> **Crucial Rule**: Each partition of a topic can be assigned to **only one** consumer instance within a consumer group at any given time. This guarantees in-order processing per partition.

---

## PART 10: Consumer Group Scaling

How does partition-to-consumer assignment behave when scaling?

```text
Scenario A: 3 Partitions, 3 Consumers (Optimal Balance)
Partition 0 ---> Consumer C1
Partition 1 ---> Consumer C2
Partition 2 ---> Consumer C3

Scenario B: 3 Partitions, 5 Consumers (Idle Consumers)
Partition 0 ---> Consumer C1
Partition 1 ---> Consumer C2
Partition 2 ---> Consumer C3
Consumer C4 ---> IDLE (No partition assigned)
Consumer C5 ---> IDLE (No partition assigned)
* Over-provisioning consumers beyond the partition count does not increase throughput. 
  The extra consumers act as hot-standbys.

Scenario C: 3 Partitions, 2 Consumers (Overloaded Consumers)
Partition 0 ---> Consumer C1
Partition 1 ---> Consumer C2
Partition 2 ---> Consumer C1 (C1 handles two partitions)
```

> [!TIP]
> To increase processing throughput, you must scale **both** the partitions of the topic and the consumers in the group.

---

## PART 11: Consumer Rebalancing

### 1. Simple Definition
A **Rebalance** is the process where the Kafka broker coordinator reassigns partition ownership among the members of a consumer group.

### 2. Real-World Analogy: Shift Handover
If one worker in a warehouse team trips and leaves the floor, the supervisor blows a whistle, pauses all processing, and divides the missing worker's shelves among the remaining team members.

### 3. Technical Explanation: How Rebalances are Triggered
A rebalance is triggered when:
* A new consumer joins the group.
* A consumer crashes or leaves (fails to send heartbeats).
* Partitions are added to the topic.

#### Heartbeat and Timeout Configurations:
* **`session.timeout.ms`**: The time a consumer can go without sending a heartbeat before the broker assumes it has crashed and triggers a rebalance.
* **`heartbeat.interval.ms`**: How frequently the consumer sends heartbeats to the coordinator (typically set to $1/3$ of `session.timeout.ms`).
* **`max.poll.interval.ms`**: The maximum time allowed between calls to `poll()`. If your application logic takes too long to process a batch of records, the client stops polling. The broker assumes the consumer is stuck/dead and kicks it out of the group, triggering a rebalance.

```text
Stuck Processing / Rebalance Storm:
Consumer C1 Polls batch -> Processes record 1 (takes 10 mins due to slow DB) ->
max.poll.interval.ms (set to 5 mins) Exceeded -> Broker Coordinator assumes C1 dead ->
Rebalance Triggered -> Partition 0 reassigned to C2 -> C2 Polls same batch ->
Processes record 1 -> Rebalance Triggered again (C2 also takes too long) -> Infinite Loop
```

#### Rebalance Protocols
1. **Eager Rebalance**: Suspending all consumer activity, revoking all partition assignments, and then reassigning them. This causes a stop-the-world pause.
2. **Cooperative Sticky Rebalance**: Only partitions that need to be moved from one consumer to another are revoked. The rest of the consumers continue processing uninterrupted.

---

## PART 12: Offset Management

### 1. Simple Definition
An **Offset** is a placeholder marking the read progress. The **Committed Offset** is the point up to which a consumer group has verified and stored its progress.

### 2. Technical Explanation: Offset Progress Diagram
```text
                         Partition 0 Log
  +-----+-----+-----+-----+-----+-----+-----+-----+-----+
  |  0  |  1  |  2  |  3  |  4  |  5  |  6  |  7  |  8  |
  +-----+-----+-----+-----+-----+-----+-----+-----+-----+
                             ^                 ^
                             |                 |
                      Committed Offset      Current Position
                       (Offset = 4)         (Offset = 7)
```
* **Current Position**: The next offset the consumer will fetch during the next `poll()`.
* **Committed Offset**: The last offset saved to the `__consumer_offsets` topic. If the consumer crashes, it will resume reading from this committed offset.
* **Auto Reset Configurations (`auto.offset.reset`)**:
  * `earliest`: Start reading from the absolute beginning of the log.
  * `latest`: Start reading only new events arriving after the consumer starts.

---

## PART 13: Consumer Lag

### 1. Simple Definition
**Consumer Lag** is the delay between the production of events and their consumption.

### 2. Technical Explanation
$$\text{Lag} = \text{Log End Offset (LEO)} - \text{Committed Offset}$$
If a producer writes up to offset 10,000, and your consumer group has only committed up to offset 9,200, your **Lag is 800 events**.

```text
Log End Offset (LEO): 10,000 (Latest produced event)
Committed Offset:     9,200  (Last processed event)
                      -----
                      Lag = 800 events
```

---

## Quick Revision
* Producers serialize records, resolve partition indexes, and buffer messages in memory before transmission.
* `acks=all` combined with `enable.idempotence=true` protects against write duplicates and data loss.
* Consumers poll for batches of records; they do not receive pushed data.
* Consumer groups partition processing. A single partition can only map to one consumer in a group at any given time.
* `max.poll.interval.ms` guards against consumers that hang during processing. Exceeding it triggers a group rebalance.
* Consumer Lag is the primary operational metric to monitor consumer health.

## Common Mistakes
* **Using `enable.auto.commit=true` in financial transactions**: This can commit offsets before database operations finish, resulting in data loss if the application crashes.
* **Setting `max.poll.interval.ms` too low**: If downstream databases slow down temporarily, this triggers a cascade of rebalance storms.
* **Under-partitioning topics**: Over-provisioning consumers to process faster when partition count is low leaves extra consumers idle.

## Production Perspective
In production, monitor consumer lag using external tools like **Kafka Lag Exporter** (which scrapes Prometheus metrics) rather than running CLI queries. This prevents CPU overhead on the controller brokers. Set alerts on lag threshold rates rather than absolute lag numbers, as a spike in ingestion might temporarily increase absolute lag without indicating consumer failure.

## Interview Questions
1. **What does `acks=all` guarantee?** (It guarantees the message is written to the Leader and all active In-Sync Replicas before returning success to the producer).
2. **What is the difference between `session.timeout.ms` and `max.poll.interval.ms`?** (`session.timeout.ms` is heartbeat-based to detect broker communication loss; `max.poll.interval.ms` is processing-time-based to detect application thread blockages).
3. **What is a rebalance storm?** (An infinite cycle of rebalances triggered when consumers repeatedly fail to process their batches within `max.poll.interval.ms`).
4. **How do you achieve exactly-once processing on the producer side?** (Enable idempotency: `enable.idempotence=true` along with transaction configurations if writing to multiple partitions).
5. **If you have a topic with 6 partitions, and a consumer group with 10 consumers, what are the remaining 4 consumers doing?** (They remain idle, serving as hot standbys unless one of the active consumers crashes).

## Principal Engineer Thinking
When dealing with slow consumer processing, avoid scaling consumer count past partition limits. Instead:
1. Increase processing parallelism internally within the consumer application using worker thread pools (e.g., using Java's `ExecutorService`).
2. Read batches from Kafka, dispatch them to workers keyed by partition or hash to preserve ordering, and manually commit offsets only when the entire batch is completed by all workers.
