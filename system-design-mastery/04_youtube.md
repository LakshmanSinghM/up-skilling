# Chapter 4: YouTube System Design

This chapter covers the system design of an enterprise-grade video streaming and sharing platform similar to YouTube. We will analyze chunked video ingest pipelines, multi-resolution transcoding networks, Adaptive Bitrate Streaming (ABS), and global CDN architectures.

---

## 1. Problem Statement
A global video streaming platform must ingest thousands of hours of raw video uploads daily, transcode them into multiple resolutions and codecs, distribute video files to global edge locations, and serve high-quality, buffer-free streams to millions of concurrent viewers under variable network conditions.

---

## 2. Requirements

### Functional Requirements:
* Creators can upload videos and edit metadata (title, description, tags).
* Viewers can watch videos in multiple resolutions (144p, 360p, 720p, 1080p, 4K).
* Adaptive Bitrate Streaming based on real-time client network quality.
* Video search by metadata, tags, and category.
* Subscription feed showing recent uploads from subscribed channels.
* Watch history tracking.
* High-volume Live Streaming capabilities.

### Non-Functional Requirements:
* **Buffer-Free Playback**: Stream latency must remain minimal; video playback should begin in under 1 second.
* **Ingestion Scale**: Support continuous upload streams without server saturation.
* **Storage Optimization**: Video storage must scale to manage petabytes of historical files.
* **High Availability**: Delivery systems must maintain $99.999\%$ uptime.

---

## 3. Scale Estimation

### Assumptions:
* **Daily Active Users (DAU)**: 800 million.
* **Daily Video Uploads**: 500,000 videos/day.
* **Average Video Duration**: 10 minutes.
* **Average Raw Upload Size**: 200 MB (1080p source format).
* **Average Views/User/Day**: 5 videos (4 billion daily views total).
* **Peak Stream Playbacks**: 20 million concurrent streams.

---

### Calculations:

#### 1. Ingestion Bandwidth (Ingress):
* **Daily Ingest Volume**: 500,000 videos $\times$ 200 MB = 100,000,000 MB = 100 TB/day.
  $$\text{Average Ingress Throughput} = \frac{100 \text{ TB}}{86,400 \text{ sec}} \approx 1.157 \text{ GB/sec (9.25 Gbps)}$$

#### 2. Streaming Bandwidth (Egress):
Assume average playback resolution is 480p (requiring 1 Mbps bandwidth).
* **Concurrent Egress**: 20 million active streams $\times$ 1 Mbps = 20 Terabits/sec (Tbps).
  * *Reasoning*: This volume of egress traffic cannot be served from a single data center. It must be distributed across global CDN points of presence.

#### 3. Storage Estimates:
* **Raw Uploads**: 100 TB/day.
* **Transcoded Formats**: Transcoding videos into multiple resolutions (360p, 720p, 1080p, 4K) and codecs (H.264, VP9, AV1) increases the storage footprint by approximately $150\%$ of the raw file size.
  * **Daily Storage Requirement**: 100 TB + 150 TB = 250 TB/day.
  * **Annual Storage Requirement**: $250 \text{ TB/day} \times 365 \text{ days} \approx 91.25 \text{ PB/year}$.

---

## 4. Video Ingest & Transcoding Architecture

```text
                  CREATOR CLIENT
                        |
            (1) POST /videos/upload-session
                        v
                  Upload Service
                        |
            (2) Returns Session Token & Target Bucket
                        v
                  CREATOR CLIENT ----- (3) Direct Chunked Upload ----> Temporary S3
                                                                            |
                                                                    (4) Upload Complete
                                                                            v
  Processed S3 <--- (7) Write --- Transcoding Workers <--- (6) Fetch --- Kafka Topic
  - Manifests (.m3u8)               (Spring Boot / FFMPEG)                [raw-uploaded]
  - Segment Chunks (.ts)
        |
        v
  Globally Distributed CDN Edge Nodes ----> Viewers
```

---

## 5. Request Flow
1. **Initialize Session**: The creator initializes an upload session. The `Upload Service` returns a session token and allocates an S3 target bucket.
2. **Chunked Upload**: The client uploads the video file in 5MB binary chunks sequentially, allowing the system to resume uploads if the connection drops.
3. **Transcoding Notification**: Once all chunks are uploaded, the `Upload Service` publishes a `raw-uploaded` event to Kafka.
4. **Transcoding Pipeline**: A pool of `Transcoding Workers` picks up the event:
   * It splits the raw video into 10-second segment files (`.ts` format).
   * It transcodes these segments into multiple resolutions (360p, 720p, 1080p) using codecs like H.264 and VP9.
   * It generates index files (**Manifest files**: `.m3u8` or `.mpd` formats) listing the available resolutions and the file paths for the video segments.
5. **CDN Distribution**: Transcoded segments and manifests are written to the processed S3 bucket and synchronized with global CDN nodes.

---

## 6. Video Transcoding Deep Dive

### Why can't YouTube store only the original video?
* **Codec Compatibility**: Different client platforms support different video codecs (e.g., Safari uses H.264/H.265; Chrome supports VP9/AV1).
* **Bandwidth Variance**: Viewers streaming on high-speed fiber networks can decode 1080p or 4K files, while mobile users in low-connectivity areas require 360p or 480p streams to prevent buffering.

---

## 7. Adaptive Bitrate Streaming (ABS)

Adaptive Bitrate Streaming allows the client player to dynamically adjust the stream quality based on real-time network throughput.

```text
                                CLIENT PLAYER
                                      |
                         Downloads Manifest File (.m3u8)
                                      |
                     Monitors network download speeds
                                      |
                 +--------------------+--------------------+
                 | Network speed is 10 Mbps                | Network speed drops to 1.5 Mbps
                 v                                         v
       Fetch Segment 01 (1080p)                 Fetch Segment 02 (480p)
       [High resolution chunk]                  [Low resolution chunk]
```

* **Protocols**: **HLS (HTTP Live Streaming)** (Apple standard, uses `.ts` segments) and **MPEG-DASH** (industry standard, uses `.m4s` segments).
* **Manifest Mechanics**: The manifest file lists the available bitrates and segment paths. The player reads the manifest and requests the optimal segment chunk sequentially:
  ```text
  #EXTM3U (HLS Manifest Example)
  #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
  360p/index.m3u8
  #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
  720p/index.m3u8
  #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
  1080p/index.m3u8
  ```

---

## 8. Database Design (Data Model)

### PostgreSQL Schema (Relational Metadata)

#### 1. Table: `channels`
```sql
CREATE TABLE channels (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    user_id BIGINT UNIQUE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 2. Table: `videos`
```sql
CREATE TABLE videos (
    id VARCHAR(20) PRIMARY KEY, -- Base64 unique hash ID (e.g., dQw4w9WgXcQ)
    channel_id BIGINT NOT NULL REFERENCES channels(id),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    duration_seconds INT NOT NULL,
    manifest_url VARCHAR(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_videos_channel ON videos(channel_id);
```

#### 3. Table: `video_metadata`
```sql
CREATE TABLE video_metadata (
    video_id VARCHAR(20) PRIMARY KEY REFERENCES videos(id),
    views_count BIGINT DEFAULT 0,
    likes_count BIGINT DEFAULT 0,
    category VARCHAR(50),
    tags TEXT[]
);
```

---

## 9. Global CDN Cache Topology

Videos cannot be served directly from primary database servers; doing so would saturate backend network interfaces.

```text
  User View Request -> CDN Edge Server -> Cache Hit -> Returns Video Segment
                                       |
                                   Cache Miss
                                       |
                                       v
                             Regional Origin Server
                                       |
                                   Cache Miss
                                       |
                                       v
                                Origin S3 Bucket
```

### Cache Hierarchy:
* **CDN Edge Nodes**: Cache the most popular videos (representing $10\%$ of uploads but driving $90\%$ of views).
* **Regional Origin Servers**: Cache medium-popularity videos.
* **Origin S3 Storage**: Stores all transcoded video files. If a user requests a low-popularity, long-tail video, the CDN pulls the segments from the origin S3 bucket, caching them along the return path.

---

## 10. Watch History Logging (Kafka Aggregation)

### The Challenge:
Logging watch progression events (e.g., "User watched video X up to minute Y") every 5 seconds for millions of viewers generates high write volumes.

### The Solution:
* The client sends watch events to the `History API` at regular intervals.
* The API writes these events to the Kafka topic `watch-history-events`.
* The `History Service` consumer groups reads the events, aggregates the offsets, and updates the user's watch history position in a NoSQL database (Cassandra) using write-behind batching, reducing write overhead on the primary datastores.

---

## 11. Trade-offs: Dynamic Transcoding vs Pre-Transcoding

### Dynamic Transcoding (Transcode on demand):
* **Pros**: Saves storage space; videos are transcoded only when requested by a viewer.
* **Cons**: Introduces high CPU overhead and latency when a video is first accessed.

### Pre-Transcoding (Recommended for Scale):
* **Pros**: Low latency; all resolutions and codecs are transcoded immediately upon upload, ensuring buffer-free streaming.
* **Cons**: High storage costs; the system must store multiple file formats for videos that may receive low view counts.
  * *Fix*: Optimize storage using lifecycle policies. If a video receives few views over 30 days, delete the higher-resolution formats (e.g., 4K, 1080p) and retain only 360p and 720p files.

---

## 12. Interview / System Design Questions (YouTube-focused)

### Q1: How do you handle a viral video driving millions of concurrent views?
* **Expected Thinking**: Cache replication and network constraints at CDN edges.
* **Strong Answer**: A viral video creates hot keys at local CDN edge nodes. If a CDN node's network interface saturates, it fails. To resolve this, implement **Cache Replication**. The CDN control plane detects the traffic spike and duplicates the video segments across multiple edge servers within the same Point of Presence (PoP), distributing client read requests. We also configure clients to fetch segments from alternative edge IPs if connection latency spikes.
* **Common Mistake**: "Scale the origin database and application instances."
* **Follow-up**: "What if the viral video is a Live Stream?" (Use **HTTP chunked caching** with very short TTLs (e.g., 2 seconds) on edge servers. This allows edge nodes to cache live segment chunks and distribute them to viewers without querying the origin ingest server).

---

## 13. Complete System Design Exercises

### Exercise 4: Design YouTube Video Upload
* **Requirements**: Ingest 500,000 videos daily, transcode them into multiple resolutions, and generate HLS manifests.
* **Reference Solution**: Implements chunked S3 uploads via pre-signed URLs. Once uploads complete, S3 event notifications publish events to a Kafka cluster. A pool of worker nodes retrieves the raw files, uses FFMPEG to split and transcode them into HLS segment chunks, and writes the processed files and `.m3u8` manifests to a CDN-backed S3 bucket.
