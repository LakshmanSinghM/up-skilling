# Chapter 5: Common Infrastructure & Deep Kafka Integration

This chapter covers the shared infrastructure patterns across LinkedIn, WhatsApp, Instagram, and YouTube. We will analyze the load balancing and API gateway layers, and detail how Apache Kafka acts as the event streaming backbone for all four platforms.

---

## PART 32: Common Infrastructure Components

While each platform serves different use cases, they all share a standard infrastructure template:

```text
                                CLIENT DEVICESS
                                       |
                                  CDN / WAF
                                       |
                            Layer 4 Load Balancer (TCP)
                                       |
                            Layer 7 Load Balancer (HTTP/SSL)
                                       |
                                  API Gateway
                                       |
                   +-------------------+-------------------+
                   |                   |                   |
            Microservice A      Microservice B      Microservice C
            (Spring Boot)       (Spring Boot)       (Spring Boot)
                   |                   |                   |
                   +-------------------+-------------------+
                                       |
                                 Kafka Cluster
```

### 1. Load Balancing Layers:
* **Layer 4 Load Balancers (L4)**: Route traffic at the transport layer (TCP/UDP) without inspecting application payloads. Used to route WebSocket traffic (WhatsApp) and distribute high-volume media ingestion streams.
* **Layer 7 Load Balancers (L7)**: Route traffic at the application layer (HTTP/HTTPS), inspecting headers, cookies, and paths. Used to handle SSL termination, execute path-based routing, and direct requests to specific microservice API gateways.

### 2. API Gateway:
The API Gateway acts as the entry point for client traffic. It manages authentication (JWT verification), rate limiting, request tracing, and routing to downstream microservices.

### 3. Microservices Framework:
Backend services are built using Spring Boot. This provides a modular, independently scalable architecture where services like `Post Service`, `Message Service`, and `User Service` can scale based on their specific resource requirements (e.g., CPU vs memory vs network).

---

## PART 33: Deep Kafka Integration Across Platforms

Apache Kafka is the central event streaming backbone for these architectures. Below is the production-grade specification of topics, partition keys, and consumer groups for each system.

---

## 1. LinkedIn Kafka Topology

```text
[Post Service] ---> Topic: linkedin.post-created ---> Partition Key: userId
                          |
                          +---> Consumer Group: feed-precomputer (Standard Users)
                          +---> Consumer Group: search-indexer (Elasticsearch updates)
                          +---> Consumer Group: notification-sender (Pushes alerts)
```

### Event Specifications:

| Event Type | Topic Name | Partition Key | Consumer Group | Retry / DLT Strategy |
| :--- | :--- | :--- | :--- | :--- |
| **Post Created** | `linkedin.post-created` | `userId` | `feed-precomputer`<br>`search-indexer` | 3 retries $\rightarrow$ `linkedin.post-created-dlt` |
| **Connection Approved** | `linkedin.conn-approved`| `userId` (Smaller ID) | `graph-updater`<br>`notification-sender`| 3 retries $\rightarrow$ `linkedin.conn-approved-dlt`|
| **Job Application** | `linkedin.job-apply` | `jobId` | `applicant-tracker`<br>`recruiter-notifier`| 5 retries $\rightarrow$ `linkedin.job-apply-dlt` |

#### Key Decisions:
* **`linkedin.post-created` Partition Key (`userId`)**: Guarantees that the chronological sequence of posts by a specific user is maintained, preventing out-of-order writes in downstream databases and feed indexes.
* **Idempotency**: Downstream consumer services use the `eventId` in the metadata header to execute client-side deduplication against database tables.

---

## 2. WhatsApp Kafka Topology

```text
[Message Service] ---> Topic: whatsapp.chat-messages ---> Partition Key: conversationId
                            |
                            +---> Consumer Group: delivery-sender (Pushes to online clients)
                            +---> Consumer Group: offline-buffered (Writes to Cassandra)
```

### Event Specifications:

| Event Type | Topic Name | Partition Key | Consumer Group | Retry / DLT Strategy |
| :--- | :--- | :--- | :--- | :--- |
| **Message Sent** | `whatsapp.chat-messages` | `conversationId` | `delivery-sender`<br>`offline-buffered`| 5 retries $\rightarrow$ `whatsapp.chat-messages-dlt`|
| **Delivery Receipt** | `whatsapp.delivery-receipt`| `conversationId` | `receipt-pusher` | 3 retries $\rightarrow$ drop event (non-critical) |
| **Presence Change** | `whatsapp.presence-events`| `userId` | `lastseen-updater` | No retries $\rightarrow$ drop event (high-volume) |

#### Key Decisions:
* **`whatsapp.chat-messages` Partition Key (`conversationId`)**: Using `conversationId` (e.g., `hash(userA_id, userB_id)`) guarantees that all messages sent within a single conversation land on the same Kafka partition, preserving chronological ordering.
* **Presence Change Volume**: Drop failed presence updates instead of using retries or DLTs; presence events are ephemeral and updated frequently, so retrying older states is unnecessary.

---

## 3. Instagram Kafka Topology

```text
[Like Service] ---> Topic: instagram.post-likes ---> Partition Key: postId
                          |
                          +---> Consumer Group: like-aggregator (Micro-batch DB updates)
```

### Event Specifications:

| Event Type | Topic Name | Partition Key | Consumer Group | Retry / DLT Strategy |
| :--- | :--- | :--- | :--- | :--- |
| **Post Created** | `instagram.post-created` | `userId` | `feed-precomputer`<br>`media-analyzer` | 3 retries $\rightarrow$ `instagram.post-created-dlt` |
| **Post Liked** | `instagram.post-likes` | `postId` | `like-aggregator`<br>`analytics-processor`| 3 retries $\rightarrow$ `instagram.post-likes-dlt` |
| **Story Viewed** | `instagram.story-views` | `storyId` | `story-tracker` | No retries $\rightarrow$ drop event |

#### Key Decisions:
* **`instagram.post-likes` Partition Key (`postId`)**: Routing by `postId` ensures that all like events for a specific post land on the same Kafka partition. This allows the `like-aggregator` consumer to batch update the post's counter in memory before writing to the database, reducing database lock contention.

---

## 4. YouTube Kafka Topology

```text
[Watch API] ---> Topic: youtube.watch-history ---> Partition Key: userId
                       |
                       +---> Consumer Group: history-updater (Updates Cassandra user log)
                       +---> Consumer Group: recommendation-engine (Feature generation)
```

### Event Specifications:

| Event Type | Topic Name | Partition Key | Consumer Group | Retry / DLT Strategy |
| :--- | :--- | :--- | :--- | :--- |
| **Video Uploaded** | `youtube.video-uploaded` | `videoId` | `transcode-workers`<br>`search-indexer` | 5 retries $\rightarrow$ `youtube.video-uploaded-dlt` |
| **Watch Progress** | `youtube.watch-history` | `userId` | `history-updater`<br>`recs-engine` | 3 retries $\rightarrow$ `youtube.watch-history-dlt` |
| **Subscription** | `youtube.subscriptions` | `channelId` | `sub-feed-updater` | 3 retries $\rightarrow$ `youtube.subscriptions-dlt` |

#### Key Decisions:
* **`youtube.watch-history` Partition Key (`userId`)**: Ensures that a user's watch history events are processed in order by the history consumer, preventing older progress markers from overwriting newer updates in the database.

---

## Quick Revision
* Layer 4 load balancers route at the transport layer (TCP) for WebSockets; Layer 7 load balancers route at the application layer (HTTP) for standard microservice APIs.
* Using a partition key like `userId` or `conversationId` in Kafka guarantees write ordering for that entity.
* Ephemeral events (like presence heartbeats or story views) should bypass retries and DLTs to prevent system congestion.
* Route like updates using `postId` as the partition key to enable downstream consumers to aggregate counts in memory before updating databases.

## Common Mistakes
* **Using random partition keys for conversation messages**: This leads to out-of-order message delivery at the client application.
* **Routing all media uploads through the API gateway**: This consumes substantial server bandwidth and memory, creating processing bottlenecks.
* **Enforcing retries on transient heartbeat events**: This wastes processing cycles; let older heartbeats drop in favor of newer updates.

## Production Perspective
Configure separate Kafka clusters for control plane events (e.g., job applications, account changes) and data plane events (e.g., watch history, presence pings). This protects critical system events from being delayed by high-volume data streams.
