# Chapter 3: Instagram System Design

This chapter covers the system design of an enterprise-grade photo and video sharing platform similar to Instagram. We will analyze high-throughput media ingestion pipelines, ephemeral story management, and write-heavy engagement counters.

---

## 1. Problem Statement
A photo/video sharing platform must ingest millions of high-resolution media files daily, transcode them into multiple resolutions, generate user feeds with low latency, manage 24-hour ephemeral stories, and handle viral traffic spikes (hot keys) on celebrity posts without database degradation.

---

## 2. Requirements

### Functional Requirements:
* Users can upload photos and short videos (Reels).
* Users can follow/unfollow other users.
* Users receive a home feed of posts from accounts they follow.
* Users can view and post 24-hour ephemeral Stories.
* Users can interact with posts via likes and comments.
* Users can search by username or hashtag.
* An Explore feed showing personalized post recommendations.

### Non-Functional Requirements:
* **Low Latency Media Delivery**: Photos and videos must load quickly via a global Content Delivery Network (CDN).
* **Write-Heavy Resilience**: The like and comment system must scale to handle viral traffic spikes without dropping writes.
* **High Availability**: The feed and explore services must maintain $99.99\%$ availability.
* **Storage Optimization**: Implement storage lifecycle policies to archive or compress older media.

---

## 3. Scale Estimation

### Assumptions:
* **Daily Active Users (DAU)**: 300 million.
* **Average Media Uploads**: 50 million photos/day + 10 million videos/day.
* **Average File Sizes**: Photo: 1 MB. Video (compressed): 10 MB.
* **Average Likes/Day**: 2 billion.
* **Average Feed Requests**: 10 views per user per day.

---

### Calculations:

#### 1. Ingestion Traffic (Writes/sec):
* **Photos/sec**:
  $$\text{Photo Ingestion Rate} = \frac{50,000,000 \text{ photos}}{86,400 \text{ sec}} \approx 578 \text{ uploads/sec}$$
* **Videos/sec**:
  $$\text{Video Ingestion Rate} = \frac{10,000,000 \text{ videos}}{86,400 \text{ sec}} \approx 115 \text{ uploads/sec}$$
* **Likes/sec**:
  $$\text{Likes Ingestion Rate} = \frac{2,000,000,000 \text{ likes}}{86,400 \text{ sec}} \approx 23,148 \text{ writes/sec (Peak: 50,000/sec)}$$

#### 2. Network Ingress Bandwidth:
* **Photo Bandwidth**: $578 \text{ photos/sec} \times 1 \text{ MB} = 578 \text{ MB/sec}$.
* **Video Bandwidth**: $115 \text{ videos/sec} \times 10 \text{ MB} = 1,150 \text{ MB/sec}$.
  $$\text{Total Ingress} = 578 \text{ MB/sec} + 1,150 \text{ MB/sec} = 1.728 \text{ GB/sec (13.82 Gbps)}$$

#### 3. Storage Estimates:
* **Daily Storage Ingest**:
  $$\text{Storage/Day} = (50\text{M} \times 1\text{MB}) + (10\text{M} \times 10\text{MB}) = 50\text{TB} + 100\text{TB} = 150 \text{ TB/day}$$
  $$\text{Storage/Year} = 150 \text{ TB/day} \times 365 \text{ days} \approx 54.75 \text{ PB/year}$$

---

## 4. Media Ingestion Pipeline Architecture

```text
                  CLIENT MOBILE
                        |
            (1) POST /media/presigned-url
                        v
                  Upload Service
                        |
            (2) Returns Pre-signed URL & Upload ID
                        v
                  CLIENT MOBILE -------- (3) Uploads Binary File --------> Amazon S3
                                                                               |
                                                                       (4) S3 Event Notification
                                                                               v
  Processed Storage <--- (6) Write --- Transcoding Workers <--- (5) Poll --- Kafka Topic
  (Optimized S3)                         (Spring Boot)                        [media-uploaded]
        |
        +-----> CDN Edge Nodes -----> Viewers
```

---

## 5. Media Upload Request Flow
1. **Request Pre-Signed URL**: The client requests an upload token from the `Upload Service`.
2. **Generate URL**: The `Upload Service` generates an S3 pre-signed URL and returns it alongside a unique `mediaId`.
3. **Direct Upload**: The client uploads the media file directly to the S3 bucket using the pre-signed URL, bypassing application servers.
4. **S3 Event Notification**: S3 triggers an event notification upon upload completion, publishing a `media-uploaded` event to Kafka.
5. **Transcoding & Optimization**: A pool of `Transcoding Workers` reads from the Kafka topic:
   * **Photos**: Compressed, resized into multiple dimensions (150x150 thumbnail, 600x600 feed view, 1080x1080 high-res), and saved to the processed S3 bucket.
   * **Videos**: Transcoded into H.264/AAC codecs in multiple resolutions (360p, 480p, 720p).
6. **CDN Propagation**: Processed files are cached at CDN Edge Nodes for fast retrieval.

---

## 6. Core Services
* **Upload Service**: Coordinates pre-signed URL generation and manages media upload metadata.
* **Feed Service**: Precomputes and caches user feeds.
* **Story Service**: Manages 24-hour ephemeral stories and tracks viewer stats.
* **Like/Comment Service**: Processes post engagement metrics using write-behind aggregation.

---

## 7. Database Design (Data Model)

### PostgreSQL Schema (Relational Data)

#### 1. Table: `users`
```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    profile_pic_url VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 2. Table: `posts`
```sql
CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    caption TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_posts_user_created ON posts(user_id, created_at DESC);
```

#### 3. Table: `media`
```sql
CREATE TABLE media (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT REFERENCES posts(id),
    media_type VARCHAR(20) NOT NULL, -- 'PHOTO', 'VIDEO'
    resolution VARCHAR(20) NOT NULL, -- 'THUMBNAIL', 'FEED', 'HIGH'
    s3_url VARCHAR(512) NOT NULL
);
CREATE INDEX idx_media_post ON media(post_id);
```

#### 4. Table: `likes`
```sql
CREATE TABLE likes (
    user_id BIGINT REFERENCES users(id),
    post_id BIGINT REFERENCES posts(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
);
```

---

## 8. Ephemeral Stories Architecture

Stories expire after 24 hours. Storing and querying stories using standard relational databases is inefficient.

### The Story Pipeline:
* **Write Path**: Stories are uploaded using the standard S3 pipeline. The metadata is written to the `stories` table with an expiration timestamp:
```sql
CREATE TABLE stories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    media_url VARCHAR(512) NOT NULL,
    expires_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_stories_active ON stories(user_id) WHERE expires_at > NOW();
```
* **S3 Lifecycle Policies**: Configure S3 buckets to transition raw objects to Amazon Glacier (archived storage) or delete them automatically after 24 hours to minimize storage costs.
* **Story Cache (Redis)**: Maintain active story metadata in a Redis Sorted Set (`user:stories:{userId}`) with a score equal to the story expiration timestamp. 
  * The application queries active stories using: `ZRANGEBYSCORE user:stories:{userId} <current_timestamp> +inf`.

---

## 9. Caching & Scaling Engagement Counters (Likes/Comments)

### The Challenge:
A viral post can receive 10,000 likes per second. Writing these updates directly to the database creates lock contention on the post row, degrading database performance.

### The Principal Engineer Solution: Write-Behind Caching (Aggregation)

```text
                             USER CLICKS LIKE
                                    |
                    Update Redis Counter (In-Memory)
                       - INCR post:likes:45012
                                    |
                    Publish event to Kafka topic [likes]
                                    |
                                    v
                        Like Aggregation Service
                                    |
                    Accumulates writes in micro-batches
                                    |
                                    v
                    Bulk update PostgreSQL every 5 seconds
```

1. **Redis Counters**: The `Like Service` increments the count directly in Redis: `INCR post:likes:{postId}`.
2. **Kafka Event Buffer**: The service publishes a like event to the Kafka topic `likes`.
3. **Asynchronous Batching**: The `Like Aggregation Service` polls events from Kafka, aggregates likes in memory, and executes bulk updates to the database every 5 seconds:
   ```sql
   UPDATE posts SET like_count = like_count + :batch_increment WHERE id = :post_id;
   ```
4. **Consistency Model**: The user interface reads the like count from Redis, providing immediate visual feedback, while the relational database updates asynchronously.

---

## 10. Explore Feed Recommendation Architecture

The Explore feed shows personalized content recommendations based on user engagement history.

```text
  User Interaction Log -> Kafka Stream -> Spark / Flink Feature Store
                                                   |
                                                   v
                                          CANDIDATE GENERATION
                                    (Retrieves 10,000 potential posts
                                     based on followed hashtags & user views)
                                                   |
                                                   v
                                            RANKING MODEL
                                    (Scores candidates using engagement probability)
                                                   |
                                                   v
                                            FILTER PIPELINE
                                    (Filters out viewed, flagged, or duplicate posts)
                                                   |
                                                   v
                                            EXPLORE CACHE
                                    (Saves top 500 post IDs in Redis ZSET)
```

---

## 11. Trade-offs: Direct S3 Uploads vs Application Gateway Uploads

### Uploading via Application Gateway:
* **Pros**: The gateway can validate file payloads, inspect headers, and authenticate users before files are written to storage.
* **Cons**: Consumes substantial application server bandwidth and memory, creating processing bottlenecks under high upload volumes.

### Direct S3 Uploads via Pre-Signed URLs (Recommended):
* **Pros**: Offloads file transfer bandwidth to S3, allowing application servers to handle more API requests.
* **Cons**: The server cannot validate the file content in real-time. 
  * *Fix*: Implement post-upload validation. S3 event notifications trigger workers to verify file integrity and delete invalid files asynchronously.

---

## 12. Interview / System Design Questions (Instagram-focused)

### Q1: How would you prevent a cache stampede if a celebrity's post falls out of the Redis cache?
* **Expected Thinking**: Explain how cache stampedes occur and how to prevent them using mutual exclusion locks.
* **Strong Answer**: If a popular post's cache expires, thousands of concurrent read requests will miss the cache and attempt to query the database simultaneously, overloading the database. To prevent this, implement a **distributed lock** using Redis (`SET key value NX PX 5000`). The first thread to experience a cache miss acquires the lock and queries the database to rebuild the cache. Subsequent threads fail to acquire the lock, wait briefly, and retry reading from the cache.
* **Common Mistake**: "Set a very long TTL on all celebrity posts."
* **Follow-up**: "Can we calculate expiration times dynamically to prevent stampedes?" (Yes. Use **probabilistic early expiration** (XFetch algorithm) to rebuild the cache before the TTL expires).

---

## 13. Complete System Design Exercises

### Exercise 3: Design Instagram Feed
* **Requirements**: Serve a photo/video feed to 300M DAUs under 200ms.
* **Reference Solution**: Implements the hybrid fan-out model (detailed in Chapter 1, Section 11). Standard posts are pushed to followers' Redis ZSETs; celebrity posts are merged dynamically on read. The feed service retrieves media assets (URLs) optimized for the client device's resolution and fetches cached likes/comments counters from Redis.
