# Chapter 5: Production Operations, Failure Playbooks, and Infrastructure

This chapter covers the operational side of Kafka. We will analyze why brokers fail, build a monitoring stack, detail Kubernetes deployment architectures, and lay out 10 step-by-step incident response playbooks.

---

## PART 22: Incident Response Playbooks (10 Production Scenarios)

### Scenario 1: Kafka Broker Crashes Suddenly
* **Symptoms**: Applications log `DisconnectException` or `TimeoutException`. Kafka UI shows a broker offline.
* **Possible Causes**: Out of Memory (OOM) killer, disk full, or JVM crash.
* **How to Investigate**:
  * Check the system logs: `journalctl -u kafka` or check `/var/log/messages` for OOM signs: `dmesg -T | grep -i oom`.
  * Check Kafka logs for JVM heap failures or disk I/O errors.
* **Fix**: Restart the broker. If it crashed due to OOM, adjust heap size configurations.
* **Prevention**: Set JVM heap size to 50% of system memory (leave the rest for OS Page Cache). Implement swap limits.

---

### Scenario 2: Disk Volume Becomes 100% Full
* **Symptoms**: Broker transitions to read-only or shuts down completely. Disk capacity alerts trigger.
* **Possible Causes**: High traffic volume, long retention settings, or log compaction failures.
* **How to Investigate**:
  * Run `df -h` on the broker host to inspect volume usage.
  * Check log directories to identify the largest partition folder: `du -sh /var/lib/kafka/data/* | sort -rh`.
* **Fix**: Temporarily reduce the retention time of the largest topic:
  ```bash
  docker exec -it kafka-kraft kafka-configs --bootstrap-server localhost:9092 --alter --entity-type topics --entity-name order-events --add-config retention.ms=86400000
  ```
* **Prevention**: Set a size-based retention limit (`retention.bytes`) alongside time-based limits to prevent log directories from exceeding disk size.

---

### Scenario 3: Consumer Instance Crashes or Restarts
* **Symptoms**: Lag spikes on specific partitions. Heartbeat timeout warnings logged on the broker.
* **Possible Causes**: JVM crash, unhandled exceptions, or node restarts.
* **How to Investigate**:
  * Search consumer logs for exceptions or exit codes.
  * Run `kafka-consumer-groups --describe` to check for unassigned partitions.
* **Fix**: Restart the consumer application instance.
* **Prevention**: Configure container orchestrators (e.g., Kubernetes) to restart failed pods automatically. Ensure exception handling in application consumer loops is robust.

---

### Scenario 4: Consumer Lag Grows Continuously
* **Symptoms**: Ingestion latency increases. Downstream dashboards are delayed.
* **Possible Causes**: Processing throughput is lower than ingestion rate. Slow database writes.
* **How to Investigate**:
  * Inspect the consumer lag metrics.
  * Run thread dumps on the consumer application to locate bottlenecked code.
* **Fix**: Add more consumer instances to the group (up to the topic's partition count).
* **Prevention**: Implement batch database writes or increase the partition count of the topic to allow higher parallelism.

---

### Scenario 5: Consumer Group Keep Rebalancing (Rebalance Storm)
* **Symptoms**: Processing throughput drops to near-zero. Logs show repeated `RebalanceInProgressException` messages.
* **Possible Causes**: A consumer thread takes longer than `max.poll.interval.ms` to process a batch, causing the broker coordinator to kick it out of the group.
* **How to Investigate**:
  * Search consumer application logs for: `MaxPollIntervalException` or `Consumer coordinate rebalance`.
  * Track time spent per batch of records.
* **Fix**: Increase `max.poll.interval.ms` in the consumer properties, or decrease `max.poll.records` to process fewer events per poll.
* **Prevention**: Offload heavy computations to an asynchronous thread pool instead of processing long-running tasks in the main polling thread.

---

### Scenario 6: Poison Message Blocks Partition Processing
* **Symptoms**: Processing halts on a partition. Consumer logs show parsing exceptions repeatedly.
* **Possible Causes**: A producer published an event with a schema mismatch or corrupt payload.
* **How to Investigate**:
  * Identify the current offset that the consumer is stuck on.
  * Fetch and inspect the offending record payload:
    ```bash
    docker exec -it kafka-kraft kafka-console-consumer --bootstrap-server localhost:9092 --topic order-events --partition 0 --offset <stuck_offset> --max-messages 1
    ```
* **Fix**: Skip the offset by resetting the consumer group's offset forward by one:
  ```bash
  docker exec -it kafka-kraft kafka-consumer-groups --bootstrap-server localhost:9092 --group notification-group --reset-offsets --to-offset <stuck_offset + 1> --topic order-events:0 --execute
  ```
* **Prevention**: Integrate a Dead Letter Topic (DLT) strategy to quarantine corrupt payloads automatically instead of throwing exceptions that halt processing.

---

### Scenario 7: Producer Cannot Publish (Timeout Exceptions)
* **Symptoms**: Producers log `TimeoutException: Failed to update metadata after 60000ms`.
* **Possible Causes**: Network partition, DNS resolution failures, or broker controller down.
* **How to Investigate**:
  * Use `ping` or `telnet` from the producer environment to test broker port connectivity: `telnet <broker-ip> 9092`.
  * Check broker logs for listener binding or routing errors.
* **Fix**: Verify advertised listener settings. Resolve DNS issues or restart blocked network routes.
* **Prevention**: Configure redundant bootstrap server lists (`broker-1:9092,broker-2:9092`) in the producer properties.

---

### Scenario 8: Partition Becomes Under-Replicated
* **Symptoms**: Prometheus alerts for under-replicated partitions trigger.
* **Possible Causes**: A follower broker crashed, experienced network isolation, or is falling behind.
* **How to Investigate**:
  * Run topic diagnostics to identify the offline replica:
    ```bash
    docker exec -it kafka-kraft kafka-topics --bootstrap-server localhost:9092 --describe --under-replicated-partitions
    ```
* **Fix**: Inspect the offline follower broker's logs and system metrics. Resolve any network bottlenecks or restart the broker process.
* **Prevention**: Distribute replicas across separate availability zones and physical hardware racks.

---

### Scenario 9: One Partition Becomes Extremely Hot
* **Symptoms**: CPU usage spikes on one broker while others remain idle. Lag spikes on a single partition.
* **Possible Causes**: Bad partition key routing, causing a disproportionate number of events to land on one partition.
* **How to Investigate**:
  * Run the CLI consumer and group messages by key to check for skewing.
  * Monitor network bytes-in metrics across brokers.
* **Fix**: Update the producer's routing logic. Add random salt to partition keys for high-volume entities to distribute writes.
* **Prevention**: Avoid using low-cardinality values (e.g., country codes) as partition keys.

---

### Scenario 10: Connection Failure to Kafka from Application
* **Symptoms**: Application fails to start, logging: `Connection to node -1 could not be established`.
* **Possible Causes**: Misconfigured listeners on the broker or network routing blocks.
* **How to Investigate**:
  * Check the advertised listener configuration on the broker.
  * Verify the application is attempting to connect to the correct listener port (e.g., host listener port vs internal container listener port).
* **Fix**: Align broker advertised listener IP and port mapping with the client network environment.
* **Prevention**: Use separate listener interfaces for internal inter-broker communications and client traffic.

---

## PART 23: Why Brokers Fail (Resource Exhaustion Checklist)

When a Kafka broker fails in production, use this priority checklist to diagnose the bottleneck:

* [ ] **Disk Space Exhaustion**: If disk utilization hits 100%, the OS page cache fails and the broker terminates to protect data logs from corruption.
* [ ] **Disk I/O Bottleneck**: Heavy read traffic (from consumers reading cold, non-cached data) can saturate disk I/O, causing broker threads to block.
* [ ] **File Descriptor Exhaustion**: Every log segment, index, and client connection consumes a file descriptor. If the OS limit is hit, the broker cannot accept new connections or roll log segments.
* [ ] **Garbage Collection (GC) Pauses**: Long JVM GC pauses ("Stop-the-World") can cause the broker to miss heartbeats with the cluster controller, triggering false offline detections and elections.
* [ ] **Network Saturation**: Replication traffic plus client read/write traffic can saturate network interfaces, leading to timeout errors.

---

## PART 24: Monitoring Architecture

```text
               +------------------+     +------------------+
               |   Kafka Broker   |     |   Kafka Broker   |
               +------------------+     +------------------+
                        |                        |
             (Exposes JMX Metrics via prometheus-jmx-exporter)
                        \                        /
                         v                      v
                  +--------------------------------+
                  |       Prometheus Server        | <--- Scrapes metrics every 15s
                  +--------------------------------+
                                  |
                        (Feeds Grafana Board)
                                  v
                  +--------------------------------+
                  |       Grafana Dashboard        |
                  +--------------------------------+
```

### Core Metrics to Monitor & Alert On

| Metric Name | Target Source | Type | Critical Alert Threshold | Description |
| :--- | :--- | :--- | :--- | :--- |
| `UnderReplicatedPartitions` | JMX MBean | Gauge | $> 0$ | Replicas failing to keep up with leader. Indicates host or network degradation. |
| `OfflinePartitionsCount` | JMX MBean | Gauge | $> 0$ | Partitions with no active leader. Clients cannot read or write to these. |
| `ActiveControllerCount` | JMX MBean | Gauge | $\neq 1$ in cluster | Ensures exactly one broker acts as the metadata controller. |
| `NetworkProcessorAvgIdlePercent` | JMX MBean | Gauge | $< 0.3$ (30% idle) | Network threads saturated. Ingestion bottlenecks imminent. |
| `RecordsLag` | Client Metric | Gauge | System-dependent | Backlog of unprocessed messages in consumer groups. |

---

## PART 25: Kubernetes Deployment (StatefulSets)

### 1. StatefulSet Architecture
To run Kafka on Kubernetes, deploy it as a **StatefulSet** rather than a Deployment.
* **Persistent Broker Identity**: Brokers require stable network IDs (e.g., `kafka-0`, `kafka-1`) and dedicated, persistent storage mappings (`PersistentVolumeClaims / PVCs`) to maintain their data logs across restarts.
* **Anti-Affinity Rules**: Enforce scheduling rules that prevent multiple broker pods from running on the same physical Kubernetes worker node.

```yaml
# Simplified Kubernetes StatefulSet Spec snippet
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka
spec:
  serviceName: kafka-service
  replicas: 3
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchExpressions:
                  - key: app
                    operator: In
                    values:
                      - kafka
              topologyKey: "kubernetes.io/hostname"
  volumeClaimTemplates:
    - metadata:
        name: kafka-data
      spec:
        accessModes: [ "ReadWriteOnce" ]
        storageClassName: "gp3-ebs" # High performance AWS block storage
        resources:
          requests:
            storage: 100Gi
```

### 2. Managed vs Self-Hosted Kafka
* **Self-Hosted (Kubernetes with Strimzi Operator)**: Complete control over configurations, zero software licensing fees. Requires substantial operational expertise to manage upgrades, rebalancing, and capacity scaling.
* **Managed (Confluent Cloud / AWS MSK)**: Offloads infrastructure operations, replication maintenance, and patching. Introduces data egress costs and vendor lock-in.

---

## Quick Revision
* Under-Replicated Partitions indicate a failure in replication sync; resolve immediately to prevent data loss.
* In Kubernetes, deploy Kafka using StatefulSets to ensure persistent broker identities and stable volume attachments.
* Avoid processing long-running tasks in the main consumer polling thread to prevent `max.poll.interval.ms` timeout rebalances.
* Monitor `OfflinePartitionsCount` and set alerts to fire if the value rises above zero.
* Run Kafka broker JVMs with Garbage Collection optimization flags (e.g., G1GC) to minimize Stop-the-World pauses.

## Common Mistakes
* **Deploying Kafka on ephemeral storage in Kubernetes**: Restarting pods on different worker nodes deletes local data logs, causing partition sync issues.
* **Configuring `max.poll.interval.ms` without reviewing consumer database timeouts**: If a database query blocks, the consumer misses its poll interval, triggering a rebalance.
* **Neglecting file descriptor limits (`ulimit -n`) on the host system**: Running out of file descriptors blocks log rolling and client socket assignments.

## Production Perspective
For critical production systems, use **Strimzi Operator** on Kubernetes. It manages the deployment, security certificates, and partition balancing (using Cruise Control) automatically, reducing manual operations.

## Interview Questions
1. **How do you diagnose and fix a consumer group stuck in a rebalance loop?** (Analyze consumer logs for `max.poll.interval.ms` violations, scale processing threads, or reduce `max.poll.records`).
2. **Why is it important to monitor Under-Replicated Partitions?** (It indicates that follower replicas are offline or lagging, increasing the risk of data loss if the leader broker fails).
3. **What is the consequence of `OfflinePartitionsCount > 0`?** (Those partitions have lost all replicas within the ISR, making them unavailable for client reads and writes).
4. **Why does Kafka require StatefulSets in Kubernetes?** (To ensure each broker pod maintains a stable network identity and retains its persistent storage mapping across restarts).
5. **How does disk saturation affect broker operation?** (If the disk fills completely, the broker transitions to read-only mode or terminates to protect data logs from corruption).

## Principal Engineer Thinking
When configuring alert paging, split alerts into priority channels:
* **P0 (Page Oncall immediately)**: `OfflinePartitionsCount > 0` or disk space $< 10\%$.
* **P1 (Slack/Ticket notification)**: `UnderReplicatedPartitions > 0` or Consumer Lag growth over 15 minutes.
* **P2 (Log/Inspect)**: Heartbeat failures or temporary client disconnections.
This prevents alert fatigue while ensuring critical system outages receive immediate attention.
