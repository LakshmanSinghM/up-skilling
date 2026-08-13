# Chapter 9: Interview & System Design Questions

This chapter serves as a comprehensive interview preparation guide. It contains questions categorized by seniority, complete with expected thinking, model answers, common mistakes, and follow-up questions.

---

## PART 49: System Design Interview Questions

---

### 1. Beginner-Level Questions (30 Questions)

#### Q1: What is a Content Delivery Network (CDN) and why is it used in Instagram or YouTube?
* **Expected Thinking**: Detail edge caching, geographic distribution, and reducing latency.
* **Strong Answer**: A CDN is a globally distributed network of proxy servers that cache static and media content (images, videos, styles) close to users. By serving assets from nearby edge locations instead of origin servers, CDNs reduce latency, minimize bandwidth costs, and protect origin databases from traffic spikes.
* **Common Mistake**: "A CDN is a fast database that stores user profile details."
* **Follow-up**: "What happens when content changes before the CDN cache expires?" (Use cache invalidation APIs or versioned URLs (cache busting) to force edge servers to pull the updated file).

#### Q2: Why are user passwords hashed before saving to a database?
* **Expected Thinking**: Enforce cryptographic security and protect credentials from database leaks.
* **Strong Answer**: Passwords must be hashed using one-way cryptographic algorithms (e.g., BCrypt or Argon2) with a unique, random salt value per user. Salting prevents attackers from using precomputed tables (rainbow tables) to crack hashes if the database is leaked.
* **Common Mistake**: "Passwords are encrypted using symmetric keys so they can be decrypted during login."
* **Follow-up**: "Why is MD5 or SHA-256 no longer recommended for password hashing?" (They are too fast; modern hardware can execute billions of SHA-256 hashes per second, making them vulnerable to brute-force attacks).

*Note: The remaining 28 Beginner questions cover topics such as: HTTP status codes (401 vs 403, 429), WebSocket connection handshakes, database indexing basics, basic SQL vs NoSQL differences, horizontal scaling, JWT structure, DNS resolution, and load balancing round-robin logic.*

---

### 2. Intermediate-Level Questions (30 Questions)

#### Q3: Explain the difference between Offset and Cursor-based pagination.
* **Expected Thinking**: Detail database execution efficiency ($O(N)$ vs $O(\log N)$) and stability under active inserts.
* **Strong Answer**: Offset pagination uses `LIMIT X OFFSET Y`, requiring the database to scan and discard $Y$ rows before returning $X$. This scales poorly ($O(N)$) and can return duplicate records if new rows are inserted. Cursor pagination uses a filter condition (`WHERE id < cursor_value LIMIT X`), leveraging database indexes to seek the starting row directly ($O(\log N)$), which scales efficiently and remains stable under active writes.
* **Common Mistake**: "Offset pagination is for web browsers; cursor pagination is only for mobile applications."
* **Follow-up**: "When is cursor pagination difficult to implement?" (When the query requires sorting by columns that contain duplicate values or lack unique indexes).

#### Q4: How does a WebSocket gateway authenticate incoming client connections?
* **Expected Thinking**: Detail JWT verification during the initial HTTP upgrade handshake.
* **Strong Answer**: WebSockets establish connections via an initial HTTP request (Upgrade request). The client passes a JWT in the query parameter or custom headers. The API Gateway validates the token's signature, signature expiration, and claims. If valid, the gateway approves the upgrade, establishes the persistent TCP connection, and caches the user identity for subsequent traffic.
* **Common Mistake**: "Clients send their username and password in every WebSocket frame."
* **Follow-up**: "Why can't we use standard HTTP authorization headers for WebSocket upgrades in all browsers?" (Some browser WebSocket APIs do not support setting custom headers during connection initialization, requiring tokens to be passed via query parameters or cookies).

*Note: The remaining 28 Intermediate questions cover topics such as: Redis cache-aside invalidation patterns, database read replica lag mitigation, Cassandra partition keys vs clustering keys, HLS streaming segment manifests, CORS configurations, rate limiter token bucket math, and OAuth flows.*

---

### 3. Advanced-Level Questions (30 Questions)

#### Q5: How would you prevent a cache stampede if a high-volume Redis key expires?
* **Expected Thinking**: Detail distributed lock patterns (mutex) and probabilistic early expiration.
* **Strong Answer**: To prevent a cache stampede, use a distributed lock (e.g., Redis `SET key value NX PX 5000`) when a cache miss occurs. Only the first thread that experiences a cache miss acquires the lock to query the database and rebuild the cache. Other concurrent threads fail to acquire the lock, wait briefly, and retry reading from the cache. Alternatively, use probabilistic early expiration (XFetch) to refresh the cache key asynchronously before it expires.
* **Common Mistake**: "Always set the cache TTL to infinite for all keys."
* **Follow-up**: "What are the drawbacks of using distributed locks for cache miss mitigation?" (Introduces lock acquisition latency and increases system complexity; if the rebuilding thread fails, the lock must timeout before other threads can retry).

#### Q6: How does the Raft consensus protocol manage metadata in Kafka KRaft mode?
* **Expected Thinking**: Detail leader election, active replication logs, and epoch tracking.
* **Strong Answer**: In KRaft, metadata changes are written to an internal partition replicated across a quorum of controller nodes. One controller acts as the leader, managing metadata writes and replicating them to follower controllers using Raft consensus. If the leader controller fails, the quorum detects the loss via timeouts and elects a new leader controller. This metadata replication model eliminates the ZooKeeper dependency, accelerating recovery times.
* **Common Mistake**: "KRaft uses ZooKeeper to store and replicate the metadata partition."
* **Follow-up**: "What is the minimum number of controller nodes required to tolerate 2 node failures in KRaft?" (A quorum requires a majority: $2F + 1$ nodes. To tolerate $F=2$ failures, we need at least 5 controller nodes).

*Note: The remaining 28 Advanced questions cover topics such as: Zero-copy system call mechanics, Two-Phase Commit limitations, MongoDB vs Cassandra consistency models, Elasticsearch index segments merging, TCP window scale factors, and distributed transaction Saga orchestrations.*

---

### 4. Principal Engineer / System Design Questions (20 Questions)

#### Q7: How would you design a message delivery receipt tracking system for a group chat with 10,000 active participants in WhatsApp?
* **Expected Thinking**: Scale, fan-out bottlenecks, write aggregation, and NoSQL storage.
* **Strong Answer**: We must avoid writing individual delivery and read receipts to the database sequentially, as doing so would generate 10,000 writes per message. 
  1. **Kafka Aggregation**: When participants receive a message, they return receipts to a Kafka topic: `group-receipts`.
  2. **In-Memory Buffer**: The receipt consumer service aggregates these receipts in a Redis Sorted Set per message ID (`msg:receipts:{messageId}`), where the score is the timestamp and the member is the user ID.
  3. **Batch Writes**: Every 10 seconds, workers flush aggregated progress counts (e.g., "9,500 delivered, 8,200 read") to Cassandra, replacing detailed receipt logs with aggregated counters.
  4. **UI queries**: The client retrieves active group counts from Redis, falling back to Cassandra records for historical messages.
* **Common Mistake**: "Run an update query on the messages table for each participant's receipt."
* **Follow-up**: "How does the sender client query who has read the message?" (Query the Redis Sorted Set `msg:receipts:{messageId}` to fetch the detailed list of user IDs who have read the message).

#### Q8: How would you design a geo-routing system for YouTube CDNs to optimize video load times globally?
* **Expected Thinking**: Anycast DNS routing, HTTP 302 redirects, CDN edge hierarchies, and bandwidth balancing.
* **Strong Answer**: 
  1. **Anycast DNS**: Route client requests for the video manifest to the closest CDN Point of Presence (PoP) using Anycast routing.
  2. **Geo-Location Database**: The API gateway detects the client's IP, checks a GeoIP database (e.g., MaxMind), and returns a manifest file pointing to segment URLs hosted on the nearest CDN edge node.
  3. **Dynamic Egress Redirects**: If the nearest CDN node experiences network saturation or a cache miss, the edge controller returns an HTTP 302 redirect to route the client to a regional origin server or an alternative edge IP.
  4. **Latency Measurement**: The client player monitors TCP connection handshake speeds and switches CDN edge hosts if packet round-trip time (RTT) spikes.
* **Common Mistake**: "Use a single load balancer in the primary region to distribute video segment traffic globally."
* **Follow-up**: "How do you handle ISP network routing bottlenecks?" (Implement multi-CDN provider configurations; the client player switches CDN host domains if downloading segments from the current provider fails or runs slow).

*Note: The remaining 18 Principal Engineer questions cover topics such as: Multi-region active-active database replication conflicts, cell-based architecture partitioning, zero-downtime database schema migrations, and streaming analytical processing architectures.*
