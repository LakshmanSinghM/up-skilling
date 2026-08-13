# Chapter 1: LinkedIn System Design

This chapter covers the system architecture of a production-grade professional networking platform similar to LinkedIn. We will analyze the scale estimations, graph traversals, and the hybrid feed generation system.

---

## 1. Problem Statement
A professional networking platform must manage a massive **Social Graph** (connections between users) while delivering a personalized, real-world feed of posts, likes, comments, and job recommendations. The core challenge is balancing graph query latency (finding 1st and 2nd-degree connections) with high-throughput feed generation.

---

## 2. Requirements

### Functional Requirements:
* Users can register, edit their professional profile, and upload resumes.
* Users can send, accept, or reject connection requests (1st-degree relationships).
* Users can follow/unfollow other professionals or company pages.
* Users can publish text posts, images, and videos.
* Users can interact with posts via likes, comments, and shares.
* Users receive a personalized home feed showing connections' activities.
* Companies can post job openings; users can search and apply for jobs.
* Users receive real-app notifications for connections, profile views, and job matches.
* Search engine to search for profiles, posts, and job listings.

### Non-Functional Requirements:
* **High Availability**: The feed and connection systems must be available $99.99\%$ of the time.
* **Low Latency**: Feed generation and profile page loading must render under 200ms.
* **Eventual Consistency**: Likes, comments, and connection state updates can propagate asynchronously (within seconds) to feed engines.
* **Durability**: User profile details and job applications must never be lost.

---

## 3. Scale Estimation

Let's calculate the system capacity using realistic, large-scale assumptions:

### Assumptions:
* **Registered Users**: 800 million.
* **Daily Active Users (DAU)**: 200 million ($25\%$ of registered users).
* **Average Connections per User**: 200 connections.
* **Daily Posts Ingestion**: 10 million posts/day.
* **Likes and Comments**: 100 million likes/day + 20 million comments/day.
* **Average Feed Read rate**: A DAU checks their feed 5 times a day.
* **Search Queries**: $10\%$ of DAUs run 5 searches/day.

---

### Calculations:

#### 1. Ingestion Traffic (Writes/sec):
* **Posts/sec**:
  $$\text{Posts QPS} = \frac{10,000,000 \text{ posts}}{86,400 \text{ sec}} \approx 115 \text{ writes/sec (Peak: 500/sec)}$$
* **Likes/sec**:
  $$\text{Likes QPS} = \frac{100,000,000 \text{ likes}}{86,400 \text{ sec}} \approx 1,157 \text{ writes/sec}$$
* **Comments/sec**:
  $$\text{Comments QPS} = \frac{20,000,000 \text{ comments}}{86,400 \text{ sec}} \approx 231 \text{ writes/sec}$$

#### 2. Query Traffic (Reads/sec):
* **Feed Reads**: 200 million DAU $\times$ 5 visits/day = 1 billion feed reads/day.
  $$\text{Feed Reads QPS} = \frac{1,000,000,000 \text{ reads}}{86,400 \text{ sec}} \approx 11,574 \text{ requests/sec (Peak: 25,000/sec)}$$
* **Search Queries**: 20 million users $\times$ 5 searches/day = 100 million searches/day.
  $$\text{Search QPS} = \frac{100,000,000 \text{ searches}}{86,400 \text{ sec}} \approx 1,157 \text{ searches/sec}$$

#### 3. Storage Estimates:
* **Post Metadata**: 10 million posts/day $\times$ 500 bytes = 5 GB/day.
* **Resumes & Media uploads**: Assume $1\%$ of posts contain media (images/videos) and 100,000 resumes are uploaded daily.
  * Media size: $100,000 \text{ posts} \times 1 \text{ MB} = 100 \text{ GB/day}$.
  * Resume size: $100,000 \text{ PDFs} \times 500 \text{ KB} = 50 \text{ GB/day}$.
  * **Total Storage**: $155 \text{ GB/day} \approx 56.5 \text{ TB/year}$.

---

## 4. High-Level Architecture

```text
                                CLIENTS (Web & Mobile)
                                           |
                                      CDN / WAF
                                           |
                                     Load Balancer
                                           |
                                      API Gateway
                                           |
      +--------------------+---------------+---------------+--------------------+
      |                    |               |               |                    |
      v                    v               v               v                    v
 User Service        Post Service    Graph Service    Feed Service         Search Service
 (Spring Boot)       (Spring Boot)   (Spring Boot)    (Spring Boot)        (Spring Boot)
      |                    |               |               |                    |
  PostgreSQL           PostgreSQL      Neo4j / Graph   Redis (Cache)       Elasticsearch
  (Profile metadata)   (Posts metadata)  Database          |                    |
      |                    |               |               v                    |
      +--------------------+---------------+--------> Kafka Cluster <------------+
```

---

## 5. Request Flow
1. **User Post Publication**: Client uploads media to S3 via pre-signed URL $\rightarrow$ metadata is sent to `Post Service` $\rightarrow$ saved to PostgreSQL $\rightarrow$ event emitted to Kafka topic `post-published`.
2. **Feed Loading**: Client requests feed $\rightarrow$ `Feed Service` checks Redis feed cache $\rightarrow$ if miss, requests connections list from `Graph Service` $\rightarrow$ polls posts from `Post Service` database $\rightarrow$ ranks and merges posts $\rightarrow$ caches result in Redis $\rightarrow$ returns payload.

---

## 6. Core Services
* **User Service**: Manages accounts, credentials, profile metadata (education, skills, experience).
* **Graph Service**: Tracks connections, follows, and connection requests. Resolves 1st and 2nd-degree paths.
* **Post Service**: Ingests posts, likes, and comments, persisting metadata in relational databases.
* **Feed Service**: Pre-computes, caches, and serves custom feeds to active users.
* **Job Board Service**: Manages job listings, applications, and applies matching algorithms.

---

## 7. Database Design (Data Model)

### PostgreSQL Schemas (Relational Data)

#### 1. Table: `users`
```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 2. Table: `profiles`
```sql
CREATE TABLE profiles (
    user_id BIGINT PRIMARY KEY REFERENCES users(id),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    headline VARCHAR(255),
    skills TEXT[],
    industry VARCHAR(100),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 3. Table: `posts`
```sql
CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    content TEXT,
    media_url VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_posts_user ON posts(user_id, created_at DESC);
```

#### 4. Table: `connections`
```sql
CREATE TABLE connections (
    user_id_1 BIGINT REFERENCES users(id),
    user_id_2 BIGINT REFERENCES users(id),
    status VARCHAR(20) NOT NULL, -- 'PENDING', 'ACCEPTED'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id_1, user_id_2)
);
CREATE INDEX idx_connections_pending ON connections(user_id_2) WHERE status = 'PENDING';
```

---

## 8. Cache Design (Redis Topology)

Redis is deployed as a cluster with Master-Slave replication to ensure high availability and read scalability.

### Caching Strategies:
* **Profile Cache (Cache-Aside)**: User profile metadata is cached using the key `profile:{userId}` with a 24-hour TTL.
  * *Write path*: On updates, the application writes to PostgreSQL first, then invalidates the Redis key (`DEL profile:{userId}`).
* **Precomputed Feed Cache (Write-Behind)**: For active users, the feed service maintains a sorted set of post IDs: `feed:{userId}`.
  * *Structure*: Redis Sorted Set (ZSET) where the score is the post timestamp and the member is the `postId`.
  * *Eviction*: Limit the size to 500 items. Older items fall out and are queried from PostgreSQL if the user scrolls deep.

```text
Redis ZSET for User Feed:
Key: feed:1001
Member: post:45021  Score: 1691973600 (Timestamp)
Member: post:45019  Score: 1691973550
Member: post:45012  Score: 1691973200
```

---

## 9. Kafka/Event Architecture

Kafka decouples post ingestion from feed propagation, search indexing, and notifications.

```text
                     Post Service
                           |
               (Publishes PostCreatedEvent)
                           v
                     Kafka Topic: post-published
                           |
        +------------------+------------------+
        |                                     |
        v                                     v
  Feed Precomputer                      Search Indexer
  (Group: feed-group)                   (Group: search-group)
  - Fan-out on write logic              - Writes post payload
  - Updates follower caches               to Elasticsearch
```

### Event Schema: `PostCreatedEvent`
```json
{
  "eventId": "e98a-2134-8c83",
  "postId": 45021,
  "userId": 1001,
  "content": "Excited to share that I have started a new position...",
  "mediaUrl": "https://s3.amazonaws.com/linkedin-media/post-123.jpg",
  "timestamp": 1691973600
}
```
* **Partition Key**: `userId`. This ensures all events created by a single user are processed sequentially by the indexers.

---

## 10. Search Architecture (Elasticsearch Indexing)

* **Why not use PostgreSQL for search?** PostgreSQL does not support fast, fuzzy text match, typo tolerance, or query scoring across millions of user documents.
* **Sync Strategy**: Elasticsearch indices are updated asynchronously via a Kafka consumer. If Elasticsearch falls behind, the message offset buffer in Kafka protects the system from failure.
* **Profile Index Document**:
```json
{
  "userId": 1001,
  "name": "Jane Doe",
  "headline": "Staff Software Engineer at Google",
  "skills": ["Java", "Spring Boot", "Kafka", "Distributed Systems"],
  "location": "San Francisco Bay Area"
}
```

---

## 11. Feed Design: The Hybrid Fan-Out Approach

A single approach to feed generation fails at LinkedIn's scale:
* **Fan-out on Write**: Pushing a new post to all followers' feed caches.
  * *Problem*: If a celebrity (e.g., Bill Gates, with 30M+ followers) posts, writing that post ID to 30 million Redis ZSETs takes several seconds, saturating memory and network bandwidth.
* **Fan-out on Read**: Pulling posts from all connections when a user opens their feed.
  * *Problem*: If an active user has 10,000 connections, querying 10,000 partition tables to fetch and sort the latest posts on every feed refresh degrades read performance.

### The Principal Engineer Hybrid Solution:

```text
                             USER PUBLISHES POST
                                      |
                           Is User a Celebrity?
                                 /          \
                              Yes            No
                              /                \
      [Celebrity Flow]                         [Standard Flow]
      - Post is saved to DB                    - Post is saved to DB
      - Skip fan-out on write                  - Fetch list of followers
      - Serve dynamically on read              - Write post ID to followers' ZSETs
```

* **Standard User (Followers < 10,000)**: Use **Fan-out on write**. When a user posts, publish an event to Kafka. The `Feed Precomputer` consumer fetches the user's followers list and inserts the `postId` into each follower's active Redis feed cache.
* **Celebrity User (Followers > 10,000)**: Use **Fan-out on read**. Do not push the post ID to followers' caches.
* **Feed Generation (On Read)**:
  1. Fetch the user's precomputed feed cache from Redis (ZSET containing standard users' posts).
  2. Query the `Graph Service` to locate followed celebrities.
  3. Query the `Post Service` to retrieve recent posts from those celebrities.
  4. Merge the celebrity posts with the cached feed, sort by relevance using the `Ranking Service`, and return the paginated payload to the client.

---

## 12. Trade-offs: Neo4j Graph Database vs PostgreSQL Graph Cache

### Neo4j Graph Database:
* **Pros**: Native graph traversal queries (e.g., finding mutual connections or 2nd-degree paths) are highly efficient.
* **Cons**: Scaling Neo4j to write-heavy workloads is complex and expensive. Sharding native graph databases is a difficult problem because graph queries require low latency across shards.

### PostgreSQL with Graph Cache (Recommended for Scale):
* **Pros**: Store connection links in PostgreSQL (flat adjacency list) for durability. Pre-compute and cache 1st-degree connection sets in Redis using Redis Sets (`SADD connections:1001 2002 3003`).
* **Cons**: Trailing 3rd-degree queries requires multi-step cache hits or database joins. However, in production, 3rd-degree traversal queries are rarely executed in real-time.

---

## 13. Interview / System Design Questions (LinkedIn-focused)

### Q1: How does LinkedIn calculate mutual connections (2nd-degree) in real-time?
* **Expected Thinking**: Trailing graphs in SQL requires slow self-joins. To calculate mutual connections quickly, use Redis Sets.
* **Strong Answer**: Retrieve User A's connections set (`connections:A`) and User B's connections set (`connections:B`) from Redis, then perform a set intersection (`SINTER connections:A connections:B`). This operation executes in $O(N + M)$ time in Redis memory, rendering results in under 5ms.
* **Common Mistake**: "Query the PostgreSQL database using a triple self-join."
* **Follow-up**: "What if the user's connection list is too large to fit in a single Redis node?" (Shard the connection sets across Redis cluster nodes using consistent hashing on the source user ID).

### Q2: How do you prevent connection request duplicate attempts?
* **Expected Thinking**: Use database uniqueness guarantees.
* **Strong Answer**: Enforce a composite primary key in the connection table: `PRIMARY KEY (user_id_1, user_id_2)`. To ensure uniqueness regardless of who sent the request, always order the IDs before writing (e.g., `user_id_1` is always the smaller ID value).
* **Common Mistake**: "Run a SELECT query to verify if a record exists before executing the INSERT." (This is vulnerable to race conditions).

---

## 14. Complete System Design Exercises

### Exercise 1: Design LinkedIn Feed
* **Requirements**: Serve a ranked feed of connections' activities to 200M DAUs under 200ms.
* **Capacity Estimation**: Ingestion QPS: 115 writes/sec. Read QPS: 11,574 reads/sec.
* **Reference Solution**: Implements the hybrid fan-out model (detailed in Section 11). Standard users write to followers' Redis ZSETs. Celebrity posts are merged dynamically on read. The merged feed is passed to a ranking service that scores posts based on user engagement history before returning the paginated payload to the client.
