# Deep System Design Mastery: Principal Engineer Production Guide

Welcome to the **System Design Mastery Handbook**. This guide is structured to take you from a basic understanding of distributed systems to a Principal Engineer-level capacity to architect, scale, operate, secure, and troubleshoot enterprise platforms. It uses real-world industry patterns to analyze four key systems: **LinkedIn, WhatsApp, Instagram, and YouTube**, mapping all designs to your existing technology stack (Java, Spring Boot, PostgreSQL, Redis, Kafka, Kubernetes, AWS, Prometheus, Grafana, Loki).

---

## Table of Contents

* [01. LinkedIn System Design](./01_linkedin.md)
  * Functional/Non-Functional Requirements & Scale Estimations
  * High-Level & Component Architectures
  * Feed Design: Hybrid Fan-out (Read vs Write, celebrity users, feed ranking)
  * Graph Relationships: Connection states, mutual connections, graph sharding
  * Search Indexing & Job Board Architecture
* [02. WhatsApp System Design](./02_whatsapp.md)
  * Real-Time Messaging Architecture (WebSockets, connection managers, heartbeats)
  * Message Lifecycle (Sent -> Delivered -> Read Receipts, offline queues)
  * Message Ordering (Conversation-level locks, Client-seq vs Server-seq ordering)
  * Group Messaging Fan-out & Presence Service (Redis TTL heartbeats)
  * End-to-End Encryption Concept Map
* [03. Instagram System Design](./03_instagram.md)
  * Media Ingestion Pipeline (Multipart S3 uploads, signed URLs, compression)
  * Feed & Stories Architecture (24-hour expiration lifecycle, viewer tracking)
  * Write-Heavy Like & Comment Counters (Redis aggregators, race conditions)
  * Explore/Recommendation Candidate Generation and Ranking Pipelines
* [04. YouTube System Design](./04_youtube.md)
  * Video Upload & Transcoding Workers (144p to 4K codec distribution)
  * Adaptive Bitrate Streaming Protocols (HLS & MPEG-DASH manifests)
  * Globally Distributed CDN Cache Hit/Miss Architectures & Edge nodes
  * Live Streaming Ingest & Chunked Segmentation Pipelines
* [05. Common Infrastructure & Deep Kafka Integration](./05_infrastructure_and_kafka.md)
  * Platform Comparison Matrix
  * Microservices, Load Balancer, Gateway topologies
  * Multi-Topic Design, Partition Keys, Consumer Groups, and DLT layouts for all 4 platforms
* [06. Data Modeling, APIs, Caching, and Sharding](./06_data_modeling_and_apis.md)
  * Complete Database Schemas (Primary keys, Indexes, Sharding strategies)
  * REST & WebSocket API Specs
  * Cursor-Based vs Offset-Based Pagination Deep Dive
  * Redis Cache Patterns (Cache-Aside, Stampede locks, Invalidation)
  * Sharding Keys vs Kafka Partition Keys
* [07. Consistency, Reliability, Security, and Disaster Recovery](./07_consistency_reliability_security_dr.md)
  * Consistency Models (Strong vs Eventual consistency boundaries)
  * Reliability Engineering (Bulkheads, Circuit Breakers, Retries, Multi-AZ)
  * Security Architecture (OAuth/JWT, RBAC, WAF, signed URLs, rate limiting)
  * Redis Token-Bucket Rate Limiter Design
  * Disaster Recovery (Multi-region active-passive failover, RPO/RTO)
* [08. Production Failures and PE Trade-Offs](./08_pe_decisions_and_failures.md)
  * 24 Incident Playbooks (6 per platform: root cause, detection logs, fix)
  * Principal Engineer Trade-offs (REST vs Kafka, SQL vs NoSQL, WebSocket vs Polling)
* [09. Interview & System Design Questions](./09_interview_preparation.md)
  * 110+ Structured Questions (Beginner, Intermediate, Advanced, PE)
* [10. Complete System Design Exercises](./10_system_design_exercises.md)
  * 8 Full Exercises with reference solutions (Feed, Presence, Job board, stories, live streams)

---

## PART 1: System Design Fundamentals

Before building individual platforms, we must align on the distributed systems primitives used to scale them.

### 1. Primitives: Functional vs Non-Functional Requirements
* **Functional Requirements (FR)**: What the system *does*. These are user-facing features (e.g., "User can post a photo").
* **Non-Functional Requirements (NFR)**: How the system *behaves* under constraints. These are architectural characteristics:
  * **Availability**: The system's ability to remain operational and respond to requests, usually measured in "nines" (e.g., $99.99\%$ availability permits only 52.6 minutes of downtime per year).
  * **Scalability**: The system's capacity to handle growing volumes of work by adding resources (horizontal/vertical scaling).
  * **Reliability**: The probability that the system performs its required function under stated conditions for a specified period without failure.
  * **Durability**: The guarantee that once data is saved, it is not lost, even during system crashes or hardware failure.

### 2. CAP Theorem vs PACELC Theorem
The CAP Theorem states that in the event of a network partition ($P$), a distributed system must choose between Consistency ($C$) or Availability ($A$).

```text
                        NETWORK PARTITION (P)
                              /       \
                             /         \
                 Choose Consistency (C)  Choose Availability (B)
                 [Block Writes]          [Accept Out-of-sync Writes]
                 * Prioritizes accuracy   * Prioritizes uptime
                 * Example: PostgreSQL    * Example: Cassandra
```

The **PACELC Theorem** expands on CAP by addressing latency and consistency trade-offs when there is *no* partition ($E$):
$$\text{If Partition } (P) \rightarrow \text{Choose Consistency } (C) \text{ or Availability } (A); \text{ Else } (E) \rightarrow \text{Choose Latency } (L) \text{ or Consistency } (C).$$
* **Example**: MongoDB (PC/EC) prioritizes consistency. Redis/DynamoDB (PA/EL) prioritize availability and lower response times.

### 3. Stateless vs Stateful Services
* **Stateless Services**: Instances do not store client session data or state locally on disk. Any instance can process any request by reading state from shared external stores (PostgreSQL, Redis). Easily autoscaled.
* **Stateful Services**: Instances maintain state locally (e.g., active WebSocket connections, in-memory caches, database storage engines). Hard to scale; require sticky routing and stable network identities.

### 4. Load Balancing, Reverse Proxies, and API Gateways
* **Reverse Proxy (e.g., Nginx)**: Sits in front of web servers, forwarding client requests to backend services. Handles SSL termination, caching, and request compression.
* **Load Balancer**: Distributes incoming traffic across a pool of servers to optimize resource utilization and prevent bottlenecks.
* **API Gateway**: Acts as the single entry point for API traffic. Coordinates routing, JWT authentication, rate limiting, and telemetry gathering.

### 5. Database Partitioning (Sharding) vs Replication
* **Replication**: Copying data across multiple nodes to improve read performance (Read Replicas) and ensure high availability (Leader-Follower failover).
* **Sharding**: Horizontally partitioning database tables across independent hosts based on a **Shard Key**. 

```text
PostgreSQL (Monolith) -> Users Table (100M Rows)
                                 |
                          (Sharding by User ID)
                                 v
   +---------------------+ +---------------------+ +---------------------+
   | Shard 0 (User 0-33M)| | Shard 1 (User 34-66M)|| Shard 2 (User 67-100M)|
   | Node A              | | Node B              | | Node C              |
   +---------------------+ +---------------------+ +---------------------+
```

---

## SYSTEM DESIGN MENTAL MODEL: The Reusable Framework

Whenever you are presented with a system design problem, follow this structured process:

```text
Step 1: Clarify Requirements (FR & NFR)
        ↓
Step 2: Scale Estimation (QPS, storage, bandwidth, memory allocations)
        ↓
Step 3: Define APIs (Endpoints, HTTP methods, JSON payloads)
        ↓
Step 4: Design Schema & Data Model (Tables, keys, indexes, target DB choices)
        ↓
Step 5: High-Level Architecture (Client -> Gateway -> App Services -> Datastores)
        ↓
Step 6: Component Deep Dive (Identify bottlenecks: caching, message queues, CDNs)
        ↓
Step 7: Reliability & Fault Tolerance (Redundancy, rate limiting, failover)
        ↓
Step 8: PE Trade-offs & Critical Review (Bottlenecks, 10x scale issues, costs)
```

---

## Core Comparison Matrix

| Dimension | LinkedIn | WhatsApp | Instagram | YouTube |
| :--- | :--- | :--- | :--- | :--- |
| **Main Challenge** | Social Graph Traversals & Feed Generation | Low-Latency Concurrent Socket Management | Ingestion of Media & Dynamic Story Lifecycles | Video Ingest, Transcoding, and CDN Streaming |
| **Real-Time Level** | Medium (Feed polling/updates) | Extreme (WebSockets under 100ms latency) | High (Stories, comments, live views) | High (Live stream ingest, dynamic bitrates) |
| **Media Target** | Low/Medium (Resume PDFs, images, posts) | Medium (Compressed images, voice notes, video clips) | Very High (Photos, Reels, stories) | Extreme (Raw 4K videos, massive segment storage) |
| **Search Engine** | Profile & Job indexing (Elasticsearch) | Local SQLite indexing | Hashtag/User index | Metadata, title & tag search (Elasticsearch) |
| **DB Focus** | Relational Graph Model (PostgreSQL + Graph Cache) | Key-Value / Log Stores (Cassandra/DynamoDB) | Read-Replica SQL + Document Storage | Distributed Metadata Store + Columnar Analytics DB |
| **CDN Usage** | Profile Pictures, CSS, JS | Media files, Profile pictures | High (Images, Reels, Stories) | Extreme (Segment chunk delivery) |
