# Chapter 10: Complete System Design Exercises

This chapter contains 8 system design exercises. Each exercise includes requirements, capacity estimations, database schemas, API designs, Kafka models, and a reference solution.

---

## Exercise 1: Design LinkedIn Feed

### 1. Requirements
* **Functional**: Users can post text, images, or videos. Users can view a home feed of connection activities, sorted by relevance.
* **Non-Functional**: Low read latency ($< 200$ms). High availability. Support up to 200M DAUs.

### 2. Capacity Estimation
* 200M DAUs $\times$ 5 feed reads/day = 1B reads/day $\approx$ 11,574 reads/sec.
* 10M posts/day $\approx$ 115 writes/sec.

### 3. Architecture & Data Flow
```text
  User Post -> Post Service -> Save PostgreSQL -> Kafka [post-created]
                                                      |
                                                      v
                                              Feed Precomputer
                                              - Checks followers
                                              - Writes to Redis ZSETs (Standard users)
  
  Feed Request -> Feed Service -> Read Redis ZSET -> Merge Celebrity posts -> Return
```

### 4. Database Schema
```sql
CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_posts_user ON posts(user_id, created_at DESC);
```

### 5. APIs & Caching
* **API**: `GET /api/v1/feed?limit=10&cursor=postId`
* **Cache**: Redis ZSET `feed:{userId}` storing post IDs scored by publication timestamp.

### 6. Reference Solution
Implements the hybrid fan-out model. Standard user posts are fanned out to followers' Redis ZSETs on write. Celebrity posts bypass the fan-out write pipeline and are merged dynamically on read. The merged feed is passed to a ranking service that scores posts based on user engagement history before returning the paginated payload to the client.

---

## Exercise 2: Design WhatsApp Messaging

### 1. Requirements
* **Functional**: One-to-one text messaging with real-time delivery receipts (Sent, Delivered, Read) and offline message delivery.
* **Non-Functional**: Sub-second latency. Persistent WebSocket connections. Minimized message storage on server after delivery.

### 2. Capacity Estimation
* 500M DAUs $\times$ 40 messages/day = 20B messages/day $\approx$ 231,481 messages/sec.
* 100M concurrent WebSocket connections.

### 3. Architecture & Data Flow
```text
  Client A -> WebSocket Connection -> CM 1 -> Message Service -> Kafka [messages]
                                                                     |
                                  +----------------------------------+
                                  |
                                  v
                           Delivery Service -> Query Redis Routing -> CM 2 -> Client B
                                  |
                              (Offline)
                                  v
                           Save Cassandra -> APNs Notification
```

### 4. Database Schema
```sql
CREATE TABLE offline_messages (
    recipient_id bigint,
    message_id uuid,
    sender_id bigint,
    payload blob,
    PRIMARY KEY (recipient_id, message_id)
);
```

### 5. APIs & Caching
* **API**: WebSocket connection `/ws/messages?token=<JWT>`
* **Cache**: Redis hash table mapping `userId -> connectionManagerId`.

### 6. Reference Solution
Establishes WebSocket connections from client devices to connection manager instances. Incoming messages are routed through Kafka. The `Delivery Service` checks a Redis cache to locate the recipient's active connection. If online, the message is pushed to the client. If offline, the encrypted payload is written to Cassandra, and a push notification is sent to GCM/APNs. Once the recipient reconnects and acknowledges receipt, the offline messages are deleted from the database.

---

## Exercise 3: Design Instagram Feed

### 1. Requirements
* **Functional**: Post photos and videos. View a home feed of followed accounts' activities, sorted by upload timestamp and relevance.
* **Non-Functional**: High availability ($99.99\%$). Fast media loading via CDN edges. Low read latency.

### 2. Capacity Estimation
* 300M DAUs $\times$ 10 feed reads/day = 3B reads/day $\approx$ 34,722 reads/sec.
* 50M photos + 10M videos uploaded daily.

### 3. Architecture & Data Flow
```text
  Upload Request -> Upload Service -> Returns Pre-signed URL -> Client Uploads directly to S3
                                                                        |
                                                                  (S3 Event)
                                                                        v
                                                                   Kafka topic
                                                                        v
                                                                 Transcoder workers
```

### 4. Database Schema
```sql
CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    caption TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 5. APIs & Caching
* **API**: `GET /api/v1/posts?limit=10&cursor=postId`
* **Cache**: Redis ZSET containing feed post IDs, and Redis keys caching post details (metadata) using the cache-aside pattern.

### 6. Reference Solution
Uses direct-to-S3 media uploads via pre-signed URLs to offload bandwidth from application servers. S3 events trigger transcoding workers via Kafka to generate multiple resolutions. Feed assembly uses the hybrid fan-out model. Like and comment counts are aggregated in Redis and written to PostgreSQL in background batches, reducing lock contention on databases.

---

## Exercise 4: Design YouTube Video Upload

### 1. Requirements
* **Functional**: Ingest video files, transcode them into multiple resolutions, and generate stream manifests.
* **Non-Functional**: Reliability; support resumes on network failures. Highly parallelized transcoding pipeline.

### 2. Capacity Estimation
* 500,000 video uploads/day.
* 100 TB raw media storage required daily.

### 3. Architecture & Data Flow
```text
  Creator Client -> Request Upload Session -> Upload Service -> Return S3 Target
        |
  (Uploads in 5MB sequential chunks) -> Temporary S3 Bucket -> S3 Event -> Kafka Topic
                                                                                |
                                                                                v
                                                                        Transcoder workers
```

### 4. Database Schema
```sql
CREATE TABLE videos (
    id VARCHAR(20) PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    manifest_url VARCHAR(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 5. APIs & Caching
* **API**: `POST /api/v1/videos/upload-session` (Initializes chunked upload).
* **Cache**: Cache active upload session state in Redis using key: `session:{uploadToken}`.

### 6. Reference Solution
Uses chunked file uploads directly to S3. Once all chunks are written, S3 events publish message tasks to Kafka. A pool of worker nodes retrieves raw files, splits them into 10-second segments (`.ts`), transcodes them in parallel using FFMPEG into multiple resolutions (360p, 720p, 1080p), and generates HLS manifest files (`.m3u8`). Manifests and segment files are saved to the processed S3 bucket and synchronized with global CDN edge servers.

---

## Exercise 5: Design YouTube Video Streaming

### 1. Requirements
* **Functional**: Adaptive Bitrate Streaming (HLS/DASH) matching client network bandwidth.
* **Non-Functional**: Buffer-free playbacks. Start playbacks under 1 second. Minimize origin storage reads.

### 2. Capacity Estimation
* 4B daily video views.
* 20M concurrent playback streams.
* Egress peak bandwidth: 20 Tbps.

### 3. Architecture & Data Flow
```text
  User Request -> DNS Geo-Route -> CDN Edge Server -> Cache Hit -> Return Segment
                                         |
                                     Cache Miss
                                         |
                                         v
                               Regional Origin Server -> Origin S3 Bucket
```

### 4. Database Schema
```sql
CREATE TABLE video_metadata (
    video_id VARCHAR(20) PRIMARY KEY,
    views_count BIGINT DEFAULT 0,
    likes_count BIGINT DEFAULT 0
);
```

### 5. APIs & Caching
* **API**: `GET /api/v1/videos/{videoId}/manifest.m3u8`
* **Cache**: Globally distributed CDN edge caches storing video segment files.

### 6. Reference Solution
Deploy regional CDN Point of Presence (PoP) edge nodes to cache popular video segments. The client player requests the HLS manifest first. It reads the available resolutions, measures download speeds, and requests segment chunks matching its bandwidth, adjusting quality dynamically to prevent buffering. Cold, low-popularity video segments are retrieved from origin S3 buckets on demand, with segment files cached along the return path to improve performance.

---

## Exercise 6: Design WhatsApp Presence

### 1. Requirements
* **Functional**: Track user online/offline status and display their last seen timestamp.
* **Non-Functional**: Scalable; handle pings from 100M concurrent users without saturating databases.

### 2. Capacity Estimation
* 100M concurrent users sending pings every 5 seconds = 20M requests/sec.
* Writing 20M writes/sec directly to PostgreSQL is not feasible on standard hardware.

### 3. Architecture & Data Flow
```text
  Client Ping -> Presence Service -> Redis (Atomic Write: SET presence:1001 online EX 15)
  
  Query Status -> Presence Service -> Check Redis key
                                            |
                                         (Expired)
                                            v
                                     Read DB last_seen
```

### 4. Database Schema
```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    last_seen_timestamp TIMESTAMP
);
```

### 5. APIs & Caching
* **API**: Client sends keep-alive heartbeats over active WebSocket connections.
* **Cache**: Redis string keys tracking online status using TTL.

### 6. Reference Solution
Avoid writing user heartbeats to relational databases. Clients send heartbeats over active WebSocket connections every 5 seconds. The connection manager updates a Redis key: `presence:{userId}` with a 15-second TTL. If the user disconnects, the key expires after 15 seconds. Online status queries check the Redis key first. If null, the user is offline, and the service retrieves the last seen timestamp from Cassandra.

---

## Exercise 7: Design Instagram Stories

### 1. Requirements
* **Functional**: Post ephemeral stories that expire and disappear after 24 hours. Track story viewer lists.
* **Non-Functional**: Low latency. Automatic storage cleanup of expired files.

### 2. Capacity Estimation
* 300M DAUs $\times$ 3 stories viewed/day = 900M views/day $\approx$ 10,416 reads/sec.

### 3. Architecture & Data Flow
```text
  Post Story -> S3 Upload -> Write Metadata to DB (expires_at = NOW() + 24 hours)
                                     |
                                     v
                           Write to Redis ZSET
  
  Read Stories -> Query Redis ZRANGEBYSCORE (current_time to +inf) -> Return Active Stories
```

### 4. Database Schema
```sql
CREATE TABLE stories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    media_url VARCHAR(512) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_active_stories ON stories(user_id) WHERE expires_at > NOW();
```

### 5. APIs & Caching
* **API**: `GET /api/v1/stories?userId={userId}`
* **Cache**: Redis Sorted Set: `user:stories:{userId}` scored by story expiration timestamps.

### 6. Reference Solution
Uploads story media using the standard S3 pipeline, writing metadata records with an expiration timestamp set to 24 hours from upload. Active story references are cached in a Redis ZSET. The S3 bucket is configured with lifecycle policies to delete raw media files automatically after 24 hours. When users query stories, the application reads from the Redis ZSET, filtering out expired stories.

---

## Exercise 8: Design LinkedIn Job Search

### 1. Requirements
* **Functional**: Companies post job openings. Users search for jobs by title, skills, or location, and apply.
* **Non-Functional**: Highly available. Search queries must execute under 100ms.

### 2. Capacity Estimation
* 20M job searches/day $\approx$ 231 queries/sec.
* 100,000 job postings daily.

### 3. Architecture & Data Flow
```text
  Post Job -> Job Service -> Save PostgreSQL -> Kafka [job-posted] -> Search Indexer
                                                                          |
                                                                          v
                                                                    Elasticsearch
  
  Job Search -> Search API -> Query Elasticsearch -> Return matched postings
```

### 4. Database Schema
```sql
CREATE TABLE jobs (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    title VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,
    location VARCHAR(150),
    is_remote BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 5. APIs & Caching
* **API**: `GET /api/v1/jobs/search?q=engineer&location=remote`
* **Cache**: Cache popular job search results in Redis with a 10-minute TTL.

### 6. Reference Solution
Stores job postings in PostgreSQL for durability. When a job is posted, an event is published to Kafka. An indexing consumer picks up the event and updates the Elasticsearch `jobs` index. Search queries are routed directly to Elasticsearch, leveraging full-text indexing, fuzzy matches, and geolocation filters to return relevance-scored results. Job application resumes are uploaded to S3, with database records updated within local transactions to prevent double applications.
