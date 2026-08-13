# Chapter 8: Production Failures and PE Trade-Offs

This chapter contains 24 production incident playbooks and details core architectural trade-offs from a Principal Engineer's perspective.

---

## PART 41: Production Incident Playbooks (24 Scenarios)

---

### 1. LinkedIn Incidents (6 Playbooks)

#### Incident 1.1: Graph Service Outage Halts Connections Lookup
* **Symptoms**: User feeds load blank; profile connection counts render as zero.
* **Root Cause**: Memory saturation on Neo4j nodes due to an unindexed connection traversal query.
* **Detection**: Prometheus alert: `neo4j_heap_utilization_ratio > 0.95`. Loki log error: `OutOfMemoryError: Java heap space`.
* **Mitigation**: Update API Gateway routing to bypass Neo4j and fetch cached connection lists from Redis.
* **Long-term Fix**: Enforce query timeout limits in Neo4j configurations and index connection relationship schemas.

#### Incident 1.2: Job Indexing Backlog in Elasticsearch
* **Symptoms**: Newly posted jobs do not appear in search results for several hours.
* **Root Cause**: Ingestion worker threads blocked on database writes, causing Kafka consumer lag to increase.
* **Detection**: Grafana metric: `records_lag_job_indexer` grows linearly.
* **Mitigation**: Scale the Elastic Search indexing worker pods in Kubernetes.
* **Long-term Fix**: Implement bulk index updates in the Kafka consumer logic, replacing single-document writes.

#### Incident 1.3: Feed Refresh Timeout Spikes
* **Symptoms**: Client feed requests return HTTP 504 gateway timeout errors.
* **Root Cause**: Cache stampede on popular user feeds following a Redis node failure.
* **Detection**: Grafana: `http_response_latency` $> 2000$ms. Loki: `ConnectionTimeoutException` to database.
* **Mitigation**: Scale the database read replicas and enable Redis distributed locks (mutex) for cache misses.
* **Long-term Fix**: Implement probabilistic early expiration algorithms to refresh cache keys before they expire.

#### Incident 1.4: Notification Delivery Failure Storm
* **Symptoms**: Users do not receive push notifications for connection requests or job matches.
* **Root Cause**: APNs/FCM feedback API rate limit exceeded due to retries on invalid device tokens.
* **Detection**: Loki: `429 Too Many Requests` returned by external APNs.
* **Mitigation**: Temporarily route notifications to a Kafka DLT, bypassing the retry loop.
* **Long-term Fix**: Implement a token validator worker to remove invalid device tokens from the database.

#### Incident 1.5: Connection Request Spam Abuse
* **Symptoms**: Database write CPU spikes; connection table size inflates rapidly.
* **Root Cause**: Bot accounts sending thousands of connection requests to random profile IDs.
* **Detection**: Grafana: `postgresql_cpu_utilization > 0.9`. Loki: `DuplicateKeyException` on connection keys.
* **Mitigation**: Enable strict rate limits on the connection request API endpoint in the API Gateway.
* **Long-term Fix**: Integrate a bot detection engine in the API Gateway to flag anomalous connection request rates.

#### Incident 1.6: Profile Image Cache Invalidation Loop
* **Symptoms**: Profile picture updates do not render on client devices.
* **Root Cause**: Cache invalidation loop; updating the profile picture triggers infinite Redis invalidation events.
* **Detection**: Grafana: `redis_eviction_rate_spikes`.
* **Mitigation**: Temporarily disable profile picture invalidation logic on the profile update service.
* **Long-term Fix**: Use unique file names (hash string) for profile images in S3, updating the URL reference rather than overwriting existing files.

---

### 2. WhatsApp Incidents (6 Playbooks)

#### Incident 2.1: Connection Manager OOM Crash
* **Symptoms**: Millions of active WebSockets drop simultaneously; clients experience connection failures.
* **Root Cause**: Memory saturation; the default TCP write buffer allocated too much memory per connection under peak load.
* **Detection**: Grafana: `active_websocket_connections` drops rapidly.
* **Mitigation**: Restart Connection Manager pods in rolling phases to distribute reconnect spikes.
* **Long-term Fix**: Reduce TCP read/write buffer sizes in sysctl configuration to 4KB per socket.

#### Incident 2.2: Reconnect Storm Overloads Authentication Gateway
* **Symptoms**: Gateway CPU utilization spikes to 100%; clients fail to authenticate.
* **Root Cause**: Millions of devices attempting to reconnect and validate JWTs simultaneously after a network outage.
* **Detection**: Prometheus: `auth_gateway_cpu_saturation_ratio > 0.95`.
* **Mitigation**: Enable rate limiting on the `/ws/connect` endpoint, rejecting excess requests with HTTP 429.
* **Long-term Fix**: Configure clients to use exponential backoff with random jitter on reconnect loops.

#### Incident 2.3: Offline Queue Saturation in Cassandra
* **Symptoms**: Message delivery times increase; Cassandra write latency spikes.
* **Root Cause**: High-volume offline messages during a cellular network outage.
* **Detection**: Grafana: `cassandra_write_latency > 50ms`.
* **Mitigation**: Add temporary node instances to the Cassandra cluster.
* **Long-term Fix**: Compress encrypted message payloads before writing to Cassandra to reduce disk I/O.

#### Incident 2.4: Message Delivery Receipt Backlog
* **Symptoms**: Double gray checkmarks ($\checkmark\checkmark$) do not update to blue checkmarks, despite messages being read.
* **Root Cause**: High Kafka partition lag on the `delivery-receipt` topic.
* **Detection**: Grafana: `consumer_lag_receipt_group` grows linearly.
* **Mitigation**: Scale the delivery receipt consumer group pods in Kubernetes.
* **Long-term Fix**: Aggregate delivery receipts in memory before writing update batches to the database.

#### Incident 2.5: Typing Indicator Lag Storm
* **Symptoms**: Typing indicators do not render or display with significant delay.
* **Root Cause**: Typing events saturating Redis connection pools.
* **Detection**: Grafana: `redis_active_connections_limit_hit`.
* **Mitigation**: Temporarily drop typing indicator events at the API Gateway.
* **Long-term Fix**: Route typing events directly between clients using WebSockets, bypassing the database.

#### Incident 2.6: Group Chat Message Ingestion Backlog
* **Symptoms**: Group chat messages display with delay; single-user chats remain unaffected.
* **Root Cause**: High fan-out write latency when distributing a message to thousands of group participants.
* **Detection**: Grafana: `records_lag_group_delivery_group` spikes.
* **Mitigation**: Increase partition count on the group messages Kafka topic to scale consumer parallelism.
* **Long-term Fix**: Implement a worker thread pool to handle group fan-out operations asynchronously.

---

### 3. Instagram Incidents (6 Playbooks)

#### Incident 3.1: Viral Post Hot Key Overloads Redis Cache
* **Symptoms**: Reads on a popular post's details fail; database CPU spikes.
* **Root Cause**: A celebrity post receives millions of requests, saturating a single Redis node's network card.
* **Detection**: Grafana: `redis_network_bandwidth_saturation`.
* **Mitigation**: Implement local memory caching (JVM Cache) for the viral post ID at the API gateway layer.
* **Long-term Fix**: Enable Redis cluster read replication, distributing queries across master and replica nodes.

#### Incident 3.2: Media Transcoding Queue Backlog
* **Symptoms**: Uploaded videos display as "Processing..." and remain unavailable for streaming.
* **Root Cause**: Worker nodes running out of CPU capacity to handle large 4K video transcoding streams.
* **Detection**: Grafana: `video_transcode_queue_length > 10000`.
* **Mitigation**: Scale transcoding worker pods in Kubernetes.
* **Long-term Fix**: Implement Adaptive Ingestion; transcode uploaded videos to 360p first to make them available quickly, and transcode higher resolutions (1080p, 4K) asynchronously.

#### Incident 3.3: CDN Cache Miss Storm on Popular Stories
* **Symptoms**: Media load times increase; origin S3 egress costs spike.
* **Root Cause**: Popular story updates expire from local CDN caches, directing requests to the origin S3 bucket.
* **Detection**: Grafana: `cdn_cache_miss_ratio > 0.4`.
* **Mitigation**: Configure long-lived cache headers (`Cache-Control: max-age`) for story media on S3.
* **Long-term Fix**: Implement CDN cache pre-warming; trigger edge caches to pull new media files when popular creators post.

#### Incident 3.4: Comment Feed Invalidation Failure
* **Symptoms**: New comments do not render in the UI; cached comment logs display out of date.
* **Root Cause**: Redis invalidation keys fail to delete due to a database sync failure.
* **Detection**: Loki: `RedisConnectionException` logged by comment services.
* **Mitigation**: Manually flush cached comment keys for affected post IDs in Redis.
* **Long-term Fix**: Use versioned keys (`comment:list:{postId}:v2`) instead of invalidating keys, updating the version reference to bypass old caches.

#### Incident 3.5: Story Viewer Tracking Saturation
* **Symptoms**: Viewer lists do not update or return HTTP 500 errors.
* **Root Cause**: Writing viewer tracking logs directly to PostgreSQL saturates database write capacity.
* **Detection**: Grafana: `postgresql_active_transactions_limit_hit`.
* **Mitigation**: Disable real-time viewer tracking updates, caching viewer lists in Redis instead.
* **Long-term Fix**: Move viewer tracking logs to a NoSQL column store (Cassandra) designed for high-throughput append writes.

#### Incident 3.6: Explore Feed Generation Lag
* **Symptoms**: Users receive outdated content recommendations on their Explore page.
* **Root Cause**: Recommendation model pipeline blocked on feature extraction jobs.
* **Detection**: Grafana: `explore_recommender_latency > 1000`ms.
* **Mitigation**: Fallback to serving popular, non-personalized post recommendations.
* **Long-term Fix**: Decouple feature extraction jobs from the real-time request path, pre-computing Explore candidates asynchronously.

---

### 4. YouTube Incidents (6 Playbooks)

#### Incident 4.1: Viral Video Cache Miss Storm
* **Symptoms**: Playback starts fail; video buffer times increase globally.
* **Root Cause**: A viral video release causes a concurrent cache miss across regional CDN nodes.
* **Detection**: Grafana: `cdn_egress_drop`. Loki: `S3RateLimitExceeded` on origin buckets.
* **Mitigation**: Replicate viral video segments across multiple edge servers within the same Point of Presence (PoP).
* **Long-term Fix**: Implement origin shield caching; route all CDN edge cache misses through a central origin shield cache node to protect origin S3 buckets from rate limits.

#### Incident 4.2: Adaptive Bitrate Manifest Corruption
* **Symptoms**: Video playbacks fail with error: `Manifest Parse Error`.
* **Root Cause**: Transcoding workers failing to write trailing lines to `.m3u8` manifest files due to disk write timeouts.
* **Detection**: Loki: `IOException: No space left on device` on worker nodes.
* **Mitigation**: Clear temp files on transcoding worker hosts and restart the transcoder service.
* **Long-term Fix**: Implement manifest write validation; verify manifest file integrity before publishing S3 updates.

#### Incident 4.3: Live Stream Ingest Latency Spike
* **Symptoms**: Streamers log dropped frames; viewers experience playback pauses.
* **Root Cause**: Live video ingest servers saturated by high network ingress traffic.
* **Detection**: Grafana: `live_ingest_cpu_utilization > 0.9`.
* **Mitigation**: Redirect streaming traffic to alternative ingest server endpoints.
* **Long-term Fix**: Implement dynamic ingest server allocation, scaling ingest servers automatically based on incoming stream volumes.

#### Incident 4.4: Watch History Offset Overwrite
* **Symptoms**: Users' watch progression markers reset to earlier positions.
* **Root Cause**: Out-of-order watch progress events in the Kafka history topic overwriting newer records in the database.
* **Detection**: Loki: `Older offset timestamp detected for user`.
* **Mitigation**: Set the consumer offset reset policy to `latest` on the history consumer group.
* **Long-term Fix**: Enforce versioned database updates; update watch history records only if the incoming timestamp is newer than the existing record.

#### Incident 4.5: Recommendation Engine Timeout
* **Symptoms**: Home pages render without video recommendations.
* **Root Cause**: The ranking model service exceeding query timeouts during peak traffic.
* **Detection**: Grafana: `recommender_api_timeout_rate > 0.05`.
* **Mitigation**: Configure the home page service to serve cached recommendations or fallback categories.
* **Long-term Fix**: Implement candidate cache layers, pre-computing recommendation sets for active users asynchronously.

#### Incident 4.6: Storage Outage on Cold Videos
* **Symptoms**: Low-popularity, historical videos fail to play, returning HTTP 500 errors.
* **Root Cause**: Storage volume failures on cold storage nodes (Amazon Glacier/S3 Infrequent Access).
* **Detection**: Grafana: `s3_cold_read_errors > 0.01`.
* **Mitigation**: Reroute read requests to the backup replica S3 buckets in the secondary region.
* **Long-term Fix**: Enforce multi-region replication policies for all video segments.

---

## PART 47: Principal Engineer Trade-Offs

Below are the core architectural trade-off evaluations:

### 1. Hybrid Fan-out on Write vs Read (Feed Generation)
* **Option A: Fan-out on Write (Push)**: Write post IDs to all followers' feed caches immediately.
  * *Pros*: Low read latency; feed requests are simple Redis cache reads.
  * *Cons*: Celebrity posts create high write volume, saturating caches and databases.
* **Option B: Fan-out on Read (Pull)**: Query followed users' posts dynamically on feed requests.
  * *Pros*: Simple write path; post updates are written to one database row.
  * *Cons*: High read latency; feed requests require querying multiple databases and sorting results.
* **PE Recommendation**: **Hybrid Model**. Use fan-out on write for standard users and fan-out on read for celebrity users to balance read and write performance.

---

### 2. WebSockets vs Long Polling (Real-Time Communication)
* **Option A: WebSockets**: Maintain a single, persistent TCP connection for bidirectional traffic.
  * *Pros*: Low latency; messages are sent instantly without connection overhead.
  * *Cons*: Stateful connection management; servers must hold open socket file descriptors.
* **Option B: Long Polling**: Clients open HTTP requests that remain pending until a message is available.
  * *Pros*: Stateless; fits within standard HTTP load balancing and routing topologies.
  * *Cons*: High CPU overhead; establishing HTTP connections repeatedly consumes server resources.
* **PE Recommendation**: **WebSockets**. Real-time communication requirements require persistent connections to minimize latency and server overhead.

---

### 3. PostgreSQL (SQL) vs Cassandra (NoSQL)
* **Option A: PostgreSQL**: Relational database supporting acid transactions and complex joins.
  * *Pros*: Strong consistency; ACID compliance ensures data integrity for transactions and connections.
  * *Cons*: Hard to scale past single-node write limits without complex sharding topologies.
* **Option B: Cassandra**: Distributed NoSQL column store optimized for high-volume writes.
  * *Pros*: Horizontally scalable; partition keys distribute data and write workloads across nodes.
  * *Cons*: No native joins or ACID transactions; data modeling requires explicit partition designs.
* **PE Recommendation**: **Polyglot Persistence**. Use PostgreSQL for relational data (users, connections, profile details) and Cassandra for high-volume append logs (chat messages, watch history, viewer logs).

---

## Quick Revision
* Monitor CPU utilization and queue lag metrics to detect and prevent system bottlenecks.
* Use distributed locks (mutex) to prevent cache stampedes on popular keys during cache misses.
* Implement direct S3 uploads via pre-signed URLs to offload bandwidth from application servers.
* Use the hybrid fan-out model to balance feed generation read and write performance.
* Choose polyglot persistence (SQL + NoSQL) to match database engines with specific service requirements.

## Common Mistakes
* **Failing to implement rate limits on public API endpoints**: This leaves the system vulnerable to bot abuse and DDoS attacks.
* **Writing high-volume user heartbeats directly to relational databases**: This saturates database write capacity, leading to system outages.
* **Enforcing synchronous database writes on click/like counters**: This creates lock contention on rows, degrading database performance.
