# Chapter 6: Data Modeling, APIs, Caching, and Sharding

This chapter covers the database schemas, API structures, caching topologies, and sharding strategies for LinkedIn, WhatsApp, Instagram, and YouTube.

---

## PART 34: Database Schemas (Data Models)

Below are the production-grade database schemas for each platform.

### 1. LinkedIn Schema (PostgreSQL)
```sql
-- Profiles table with composite index for fast name/location search
CREATE TABLE profiles (
    user_id BIGINT PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    headline VARCHAR(255),
    skills TEXT[],
    industry VARCHAR(100),
    location VARCHAR(150),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_profiles_search ON profiles(last_name, first_name, location);

-- Jobs table
CREATE TABLE jobs (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL,
    title VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,
    location VARCHAR(150),
    is_remote BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_jobs_location_remote ON jobs(location, is_remote) WHERE is_remote = TRUE;

-- Job Applications
CREATE TABLE job_applications (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES jobs(id),
    user_id BIGINT NOT NULL,
    resume_s3_url VARCHAR(512) NOT NULL,
    status VARCHAR(50) DEFAULT 'SUBMITTED',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_unique_apply ON job_applications(job_id, user_id);
```

### 2. WhatsApp Schema (Cassandra NoSQL)
```sql
-- Conversations mapping
CREATE TABLE whatsapp.conversations (
    conversation_id uuid,
    last_message_id uuid,
    last_message_preview text,
    updated_at timestamp,
    PRIMARY KEY (conversation_id)
);

-- Conversation Members (for Group Chats)
CREATE TABLE whatsapp.conversation_members (
    conversation_id uuid,
    user_id bigint,
    joined_at timestamp,
    role varchar, -- 'ADMIN', 'MEMBER'
    PRIMARY KEY (conversation_id, user_id)
);

-- Message Status tracking
CREATE TABLE whatsapp.message_status (
    message_id uuid,
    recipient_id bigint,
    status varchar, -- 'SENT', 'DELIVERED', 'READ'
    updated_at timestamp,
    PRIMARY KEY (message_id, recipient_id)
);
```

### 3. Instagram Schema (PostgreSQL + NoSQL)
```sql
-- Followers relationship table
CREATE TABLE followers (
    follower_id BIGINT NOT NULL,
    followed_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (follower_id, followed_id)
);
CREATE INDEX idx_followed_list ON followers(followed_id);

-- Stories Table
CREATE TABLE stories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    media_url VARCHAR(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_active_stories ON stories(user_id) WHERE expires_at > NOW();
```

### 4. YouTube Schema (PostgreSQL)
```sql
-- Videos Table
CREATE TABLE videos (
    id VARCHAR(20) PRIMARY KEY, -- Hash value (e.g., dQw4w9WgXcQ)
    channel_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    manifest_url VARCHAR(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_videos_channel_created ON videos(channel_id, created_at DESC);

-- Watch History Table (Aggregated Log)
CREATE TABLE watch_history (
    user_id BIGINT NOT NULL,
    video_id VARCHAR(20) NOT NULL REFERENCES videos(id),
    last_position_seconds INT NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, video_id)
);
CREATE INDEX idx_history_user ON watch_history(user_id, updated_at DESC);
```

---

## PART 35 & 36: API Design & Pagination Deep Dive

### 1. REST Endpoints

#### LinkedIn Job Application:
* **Endpoint**: `POST /api/v1/jobs/{jobId}/apply`
* **Headers**: `Authorization: Bearer <JWT>`
* **Payload**:
```json
{
  "resumeS3Url": "https://s3.amazonaws.com/linkedin-resumes/user-101-resume.pdf"
}
```

#### WhatsApp WebSocket Connection:
* **Connection URL**: `ws://gateway.whatsapp.com/ws/messages?token=<JWT>&device_id=<UUID>`

#### YouTube Video Upload Session:
* **Endpoint**: `POST /api/v1/videos/upload-session`
* **Payload**:
```json
{
  "title": "My System Design Guide",
  "fileName": "guide.mp4",
  "fileSize": 104857600
}
```

---

### 2. Pagination Deep Dive: Offset vs Cursor Pagination

When retrieving feeds or message history, you must avoid offset-based pagination at scale.

```text
Offset Pagination: LIMIT 10 OFFSET 1000000
PostgreSQL parses the table, reads 1,000,010 rows from the beginning, discards 
the first 1,000,000, and returns the last 10.
  * DB overhead increases linearly; deep page requests cause high CPU and I/O load.
  * Vulnerable to skipped or duplicate records if new rows are inserted during navigation.

Cursor Pagination: LIMIT 10 WHERE id < cursor_value ORDER BY id DESC
Query utilizes an index on the ID column, directly seeking the starting record 
and reading only the next 10 rows.
  * DB execution time remains constant (O(log N)) regardless of page depth.
  * Stable under active insertions; the cursor maintains an absolute position marker.
```

#### Cursor Pagination Query Example (Instagram Feed):
```sql
SELECT id, user_id, caption, created_at 
FROM posts 
WHERE id < 45012  -- The 'cursor' (the last post ID from the previous page load)
ORDER BY id DESC 
LIMIT 10;
```

---

## PART 37: Caching Strategy (Redis)

Redis manages transient, high-volume state across these architectures.

### 1. Cache Patterns
* **Cache-Aside (LinkedIn Profiles)**: The application checks Redis for the profile. On cache miss, it reads from PostgreSQL, updates Redis, and returns the payload.
* **Write-Through**: The application writes to the cache and the database in a single transaction.
* **Write-Behind (Instagram Counter Aggregation)**: Writes are written to Redis and Kafka, updating databases asynchronously.

### 2. Cache Stampede Mitigation (Redis Mutex Lock)
```java
public Profile getProfile(String userId) {
    String cacheKey = "profile:" + userId;
    Profile profile = redisTemplate.opsForValue().get(cacheKey);
    
    if (profile == null) {
        // Cache miss: attempt to acquire lock to query DB
        String lockKey = "lock:profile:" + userId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofSeconds(5));
        
        if (Boolean.TRUE.equals(acquired)) {
            try {
                profile = postgresProfileRepo.findById(userId);
                redisTemplate.opsForValue().set(cacheKey, profile, Duration.ofHours(24));
            } finally {
                redisTemplate.delete(lockKey); // Release lock
            }
        } else {
            // Lock not acquired: wait and retry
            Thread.sleep(100);
            return getProfile(userId);
        }
    }
    return profile;
}
```

---

## PART 38: Database Sharding

When data volumes exceed the storage capacity of a single database host, you must partition tables across shards.

### Shard Key Selections:
* **LinkedIn Graph**: Shard by `userId`. All connection mappings for a user reside on the same shard, optimizing 1st-degree lookups.
* **WhatsApp Messages**: Shard by `conversationId` (e.g., `hash(userId1, userId2)`). This ensures message history for a chat session is stored sequentially on one node.
* **YouTube Metadata**: Shard by `videoId` (Base64 hash string). Distributes high-volume read queries evenly across shard instances.

---

### Sharding Keys vs Kafka Partition Keys

Choosing a database shard key is conceptually similar to selecting a Kafka partition key.

| Dimension | Database Shard Key | Kafka Partition Key |
| :--- | :--- | :--- |
| **Primary Goal** | Distributes storage footprint and transactional write queries across physical hosts. | Directs event records to a specific partition log to preserve processing order. |
| **Routing Protocol**| Consistent hashing (e.g., MurmurHash3) over shard ring networks. | Modulo hash formulas mapping keys to partition indexes. |
| **Modification Impact**| Altering shard keys requires database migrations and data redistributions. | Changing partition counts routes future key writes to different partitions. |

---

## Quick Revision
* Index tables on search fields (e.g., `last_name, first_name` for profiles) to optimize query paths.
* Do not use offset pagination for high-volume feeds; use cursor pagination to ensure constant-time queries.
* Mitigate cache stampedes on popular keys using Redis distributed locks.
* Shard databases using high-cardinality keys to distribute workloads evenly.
* Align database sharding keys with Kafka partition keys to maintain data locality.

## Common Mistakes
* **Using `OFFSET 1000000` in SQL queries**: This leads to performance bottlenecks on deep page requests.
* **Setting static, uniform Redis TTLs for all keys**: This can cause cache keys to expire simultaneously, overloading databases.
* **Selecting low-cardinality values as database shard keys**: This creates uneven storage distribution and hot shards.
