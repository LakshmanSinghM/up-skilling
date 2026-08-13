# Chapter 9: Hands-On Labs (1 to 20)

This chapter contains 20 step-by-step labs. Each lab is structured to build practical experience with Kafka, covering basic CLI operations, consumer group rebalancing, resiliency strategies, and monitoring.

---

## Lab 1: Run Kafka locally
* **Goal**: Start a single-node Kafka instance using KRaft mode.
* **Architecture**: A single container running Kafka in combined broker/controller mode.
* **Prerequisites**: Docker and Docker Compose installed.
* **Commands**:
  ```bash
  docker-compose up -d kafka
  ```
* **Expected Result**: The Kafka container starts and binds to port `9092` on the host.
* **What to Observe**: Check container status: `docker ps`. Verify port binding: `netstat -ano | grep 9092`.
* **What You Learned**: How to configure and launch a KRaft broker without ZooKeeper.
* **Production Relevance**: Provides a lightweight local development environment.

---

## Lab 2: Create topic
* **Goal**: Create a topic with specific partition and replication configurations.
* **Architecture**: Client CLI communicating with the local broker.
* **Prerequisites**: Kafka running (Lab 1).
* **Commands**:
  ```bash
  docker exec -it kafka-kraft kafka-topics --bootstrap-server localhost:9092 --create --topic lab-topic --partitions 2 --replication-factor 1
  ```
* **Expected Result**: Output logs show: `Created topic lab-topic`.
* **What to Observe**: Verify topic creation by listing existing topics: `kafka-topics --list`.
* **What You Learned**: How to define partition counts and replication factors during topic creation.
* **Production Relevance**: Topics should be provisioned explicitly; avoid relying on auto-creation.

---

## Lab 3: Create partitions
* **Goal**: Scale an existing topic by increasing its partition count.
* **Architecture**: CLI client modifying metadata on the broker.
* **Prerequisites**: Topic `lab-topic` created (Lab 2).
* **Commands**:
  ```bash
  docker exec -it kafka-kraft kafka-topics --bootstrap-server localhost:9092 --alter --topic lab-topic --partitions 4
  ```
* **Expected Result**: Output logs show: `Topic lab-topic altered`.
* **What to Observe**: Describe the topic to verify the partition count is now 4: `kafka-topics --describe`.
* **What You Learned**: You can dynamically increase partition counts, but doing so changes key routing.
* **Production Relevance**: Do not alter partition counts on topics that rely on key-based ordering.

---

## Lab 4: Produce records
* **Goal**: Publish events to a topic using the CLI console.
* **Architecture**: CLI Producer sending string payloads to the broker.
* **Prerequisites**: Topic `lab-topic` created.
* **Commands**:
  ```bash
  docker exec -it kafka-kraft kafka-console-producer --bootstrap-server localhost:9092 --topic lab-topic
  ```
* **Code/Input**:
  ```text
  event-payload-1
  event-payload-2
  ```
* **Expected Result**: Records are written to the topic logs on the broker.
* **What to Observe**: The producer process remains active, waiting for subsequent input lines.
* **What You Learned**: The console producer provides a quick way to test message ingestion.
* **Production Relevance**: Useful for debugging connectivity issues on production brokers.

---

## Lab 5: Consume records
* **Goal**: Read events from the beginning of a topic using the CLI.
* **Architecture**: CLI Consumer polling events from the broker.
* **Prerequisites**: Records produced (Lab 4).
* **Commands**:
  ```bash
  docker exec -it kafka-kraft kafka-console-consumer --bootstrap-server localhost:9092 --topic lab-topic --from-beginning
  ```
* **Expected Result**: The console outputs:
  ```text
  event-payload-1
  event-payload-2
  ```
* **What to Observe**: The consumer stays open, waiting for new events to arrive.
* **What You Learned**: How to read historical events from offset 0.
* **Production Relevance**: Useful for verifying event schema formats during troubleshooting.

---

## Lab 6: Create consumer groups
* **Goal**: Launch a consumer instance as part of a named consumer group.
* **Architecture**: CLI consumer registering with the group coordinator.
* **Prerequisites**: Topic `lab-topic` created.
* **Commands**:
  ```bash
  docker exec -it kafka-kraft kafka-console-consumer --bootstrap-server localhost:9092 --topic lab-topic --group lab-group
  ```
* **Expected Result**: The consumer registers with `lab-group` and starts polling events.
* **What to Observe**: Describe the group to verify its active status: `kafka-consumer-groups --describe --group lab-group`.
* **What You Learned**: Specifying a `--group` assigns offsets to that group ID instead of using ephemeral clients.
* **Production Relevance**: Production applications must use explicit consumer group IDs to manage offsets.

---

## Lab 7: Run multiple consumers
* **Goal**: Scale consumer capacity by running multiple instances in the same group.
* **Architecture**: Two consumer instances dividing a topic's partitions between themselves.
* **Prerequisites**: Topic with multiple partitions (Lab 3).
* **Commands**:
  Open two terminal windows and run in both:
  ```bash
  docker exec -it kafka-kraft kafka-console-consumer --bootstrap-server localhost:9092 --topic lab-topic --group lab-group
  ```
* **Expected Result**: The partitions are split between the two active consumer instances.
* **What to Observe**: Publish 4 messages with different keys; observe that they are split between the two terminals.
* **What You Learned**: Consumer groups distribute partition assignments to scale processing capacity.
* **Production Relevance**: Match partition count to target consumer instance counts to ensure even balancing.

---

## Lab 8: Stop one consumer and observe rebalancing
* **Goal**: Observe how the broker handles a consumer instance outage.
* **Architecture**: A consumer group adapting to a member disconnection.
* **Prerequisites**: Multiple consumers running (Lab 7).
* **Commands**:
  1. Stop one consumer terminal using `Ctrl+C`.
  2. In the other terminal, monitor the logs.
* **Expected Result**: The remaining consumer experiences a brief pause, then takes over the partitions previously assigned to the stopped consumer.
* **What to Observe**: Inspect the consumer group status: `kafka-consumer-groups --describe --group lab-group`.
* **What You Learned**: Kafka automatically rebalances partitions to maintain availability when a consumer goes offline.
* **Production Relevance**: Consumer restarts trigger rebalances; minimize their impact by using cooperative sticky assignors.

---

## Lab 9: Create multiple consumer groups
* **Goal**: Demonstrate how multiple consumer groups read from the same topic independently.
* **Architecture**: Two separate consumer groups polling the same topic.
* **Prerequisites**: Topic `lab-topic` with messages.
* **Commands**:
  Terminal 1:
  ```bash
  docker exec -it kafka-kraft kafka-console-consumer --bootstrap-server localhost:9092 --topic lab-topic --group group-billing
  ```
  Terminal 2:
  ```bash
  docker exec -it kafka-kraft kafka-console-consumer --bootstrap-server localhost:9092 --topic lab-topic --group group-shipping
  ```
* **Expected Result**: Both terminals receive every message published to the topic.
* **What to Observe**: Publish a message; verify that both terminals output the payload.
* **What You Learned**: Different consumer groups process the same event stream independently.
* **Production Relevance**: Enables publish-subscribe patterns where multiple microservices consume the same data feed.

---

## Lab 10: Observe independent offsets
* **Goal**: Verify that consumer groups manage their offset progress separately.
* **Architecture**: Two groups committing offsets independently.
* **Prerequisites**: Multiple groups running (Lab 9).
* **Commands**:
  Stop the consumer in `group-billing` (Terminal 1). Publish 5 messages. Restart the consumer in `group-billing`.
* **Expected Result**: `group-shipping` processes the messages immediately. The restarted `group-billing` consumer processes the 5 buffered messages upon reconnecting.
* **What to Observe**: Run `kafka-consumer-groups --describe` for both groups; note the different committed offsets.
* **What You Learned**: Offsets are stored independently per consumer group.
* **Production Relevance**: One service failing or running slow does not impact the consumption rate of other services.

---

## Lab 11: Create producer with keys
* **Goal**: Publish events with explicit partition keys.
* **Architecture**: CLI producer sending key-value pairs.
* **Prerequisites**: Topic `lab-topic` created.
* **Commands**:
  ```bash
  docker exec -it kafka-kraft kafka-console-producer --bootstrap-server localhost:9092 --topic lab-topic --property parse.key=true --property key.separator=:
  ```
* **Code/Input**:
  ```text
  user-100:{"event":"login"}
  user-200:{"event":"purchase"}
  user-100:{"event":"logout"}
  ```
* **Expected Result**: Events with key `user-100` are routed to the same partition.
* **What to Observe**: Describe partition offsets to confirm key routing: `kafka-run-class.sh kafka.tools.GetOffsetShell --bootstrap-server localhost:9092 --topic lab-topic`.
* **What You Learned**: Enforcing keys guarantees partition routing, preserving order for that key.
* **Production Relevance**: Use business entity IDs (e.g., `customerId`) as keys to guarantee sequential processing.

---

## Lab 12: Observe partition distribution
* **Goal**: Trace how keys map to specific partition directories on disk.
* **Architecture**: File inspection on the broker's local storage.
* **Prerequisites**: Keys produced (Lab 11).
* **Commands**:
  ```bash
  docker exec -it kafka-kraft ls -l /tmp/kraft-combined-logs/
  ```
* **Expected Result**: Directories matching the pattern `lab-topic-<index>` are visible.
* **What to Observe**: The size of individual segment files (`.log`) inside each partition folder.
* **What You Learned**: Kafka translates logical partitions to physical subdirectories on the host filesystem.
* **Production Relevance**: Monitor individual partition folder sizes to detect routing imbalances.

---

## Lab 13: Generate consumer lag
* **Goal**: Simulate lag by pausing a consumer while producing new events.
* **Architecture**: Active producer with an inactive/paused consumer.
* **Prerequisites**: Group `lab-group` active.
* **Commands**:
  1. Stop the consumer for `lab-group`.
  2. Run the producer and send 10 messages.
  3. Query the group lag:
     ```bash
     docker exec -it kafka-kraft kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group lab-group
     ```
* **Expected Result**: The group description shows a lag value of 10.
* **What to Observe**: The `LAG` column in the CLI output displays the number of unread events.
* **What You Learned**: How consumer lag accumulates when consumers are offline or slow.
* **Production Relevance**: Consumer lag is a key metric; set alerts to catch spikes early.

---

## Lab 14: Implement retry
* **Goal**: Implement temporary failure retries in Spring Boot.
* **Architecture**: Spring Boot `@KafkaListener` retrying processing on exception.
* **Prerequisites**: Java and Spring Boot project configured.
* **Code**:
  ```java
  @KafkaListener(topics = "retry-topic", groupId = "retry-group")
  public void consume(String message) {
      log.info("Attempting processing...");
      throw new TransientException("Temporary connection timeout");
  }
  ```
* **Expected Result**: The listener retries the message multiple times before failing.
* **What to Observe**: Check application logs for repeated processing attempts for the same record.
* **What You Learned**: How to implement retry mechanisms to recover from transient downstream errors.
* **Production Relevance**: Use retries to handle temporary network glitches without losing events.

---

## Lab 15: Implement DLT
* **Goal**: Route unprocessible events to a Dead Letter Topic (DLT) in Spring Boot.
* **Architecture**: Spring Boot routing permanent failures to a quarantine topic.
* **Prerequisites**: Spring Boot project configured.
* **Code**:
  ```java
  @RetryableTopic(attempts = "3", dltTopicSuffix = "-dlt")
  @KafkaListener(topics = "orders", groupId = "order-group")
  public void consume(OrderEvent event) {
      if (event.amount() == null) {
          throw new IllegalArgumentException("Invalid payload: amount is null");
      }
  }
  ```
* **Expected Result**: Events with null amounts are routed to `orders-dlt` after 3 failed attempts.
* **What to Observe**: Run a consumer on `orders-dlt` to verify the failed event was quarantined.
* **What You Learned**: How to quarantine poison messages to prevent them from blocking partitions.
* **Production Relevance**: Monitor DLT topics to capture and debug invalid payloads.

---

## Lab 16: Implement idempotency
* **Goal**: Enforce idempotent event consumption in Spring Boot.
* **Architecture**: Spring Boot using a PostgreSQL deduplication table.
* **Prerequisites**: PostgreSQL database running (Lab 1).
* **Code**:
  ```java
  @Transactional
  public void processEvent(String eventId, String payload) {
      // Enforce unique key constraint
      jdbcTemplate.update("INSERT INTO processed_events (event_id) VALUES (?)", eventId);
      // Run business logic
      jdbcTemplate.update("UPDATE accounts SET balance = balance + 100 WHERE id = 1");
  }
  ```
* **Expected Result**: Duplicate events result in a constraint violation, skipping the business logic write.
* **What to Observe**: Send two events with the same `eventId`; verify the account balance only increases once.
* **What You Learned**: Enforcing unique database constraints ensures safe event processing.
* **Production Relevance**: Client-side idempotency is required to handle At-Least-Once delivery duplicates.

---

## Lab 17: Implement Outbox Pattern
* **Goal**: Implement atomic database updates and event publishing.
* **Architecture**: Spring Boot writing to a business table and outbox table in one transaction.
* **Prerequisites**: PostgreSQL running.
* **Code**:
  ```java
  @Transactional
  public void createOrder(Order order) {
      orderRepository.save(order);
      OutboxEvent outbox = new OutboxEvent("order-events", order.getId(), toJson(order));
      outboxRepository.save(outbox);
  }
  ```
* **Expected Result**: The order and outbox record are committed together.
* **What to Observe**: Verify both tables contain the records. If either write fails, the entire transaction rolls back.
* **What You Learned**: The Outbox pattern ensures data consistency between databases and Kafka.
* **Production Relevance**: Resolves the dual-write problem, preventing state inconsistencies.

---

## Lab 18: Monitor Kafka using Prometheus/Grafana
* **Goal**: Collect and visualize Kafka broker metrics.
* **Architecture**: Prometheus scraping JMX metrics; Grafana displaying the dashboard.
* **Prerequisites**: Prometheus and Grafana containers added to Docker Compose.
* **Commands**:
  ```bash
  docker-compose up -d prometheus grafana
  ```
* **Expected Result**: The Grafana dashboard at `http://localhost:3000` displays broker CPU, memory, and partition counts.
* **What to Observe**: The Grafana UI updates in real-time as traffic is sent to Kafka.
* **What You Learned**: How to set up scraping configurations to monitor cluster health.
* **Production Relevance**: Critical for detecting resource saturation and tracking JVM performance.

---

## Lab 19: Deploy Kafka to Kubernetes
* **Goal**: Deploy a local Kafka cluster inside Kubernetes using Strimzi.
* **Architecture**: Strimzi Operator managing Kafka StatefulSets on Minikube.
* **Prerequisites**: Minikube and kubectl installed.
* **Commands**:
  ```bash
  kubectl create -f https://strimzi.io/install/latest?namespace=default
  kubectl apply -f https://strimzi.io/examples/latest/kafka/kafka-persistent-single.yaml
  ```
* **Expected Result**: Strimzi deploys the controller and broker pods in the default namespace.
* **What to Observe**: Verify pod status: `kubectl get pods -w`.
* **What You Learned**: How operators simplify deploying stateful workloads on Kubernetes.
* **Production Relevance**: StatefulSets ensure persistent broker IDs and volume attachments.

---

## Lab 20: Simulate broker failure
* **Goal**: Verify replication failover when a broker crashes.
* **Architecture**: A 3-broker cluster with replicated partitions.
* **Prerequisites**: Multi-node cluster running.
* **Commands**:
  1. Identify the leader for partition 0: `kafka-topics --describe`.
  2. Stop the leader broker container: `docker stop broker-1`.
  3. Re-run description: `kafka-topics --describe`.
* **Expected Result**: One of the active ISR follower brokers is promoted to leader for partition 0.
* **What to Observe**: Output shows the new leader ID under `Leader:` for partition 0.
* **What You Learned**: Kafka replication automatically handles broker outages to maintain availability.
* **Production Relevance**: Distribute replicas across separate availability zones to tolerate rack failures.

---

## Quick Revision
* Use Docker Compose with KRaft for local testing.
* Scale topics by altering partition counts, but avoid doing so on topics that rely on key-based routing.
* Run multiple consumer instances within the same group to distribute partition workloads.
* Consumer lag indicates processing backlogs; monitor lag to detect scaling bottlenecks.
* Implement retries and DLTs to handle failures without blocking partitions.
* Enforce unique database constraints to make consumer processing idempotent.

## Common Mistakes
* **Increasing partition count without reviewing producer keys**: This breaks downstream ordering guarantees.
* **Using auto-commit configurations with critical transactions**: This can lead to data loss during consumer crashes.
* **Running Kafka on ephemeral storage in Kubernetes**: Broker restarts will wipe local logs, causing synchronization issues.

## Production Perspective
Automate topic provisioning using infrastructure-as-code pipelines. Enforce client-side idempotency and monitor consumer lag metrics to maintain system reliability.
