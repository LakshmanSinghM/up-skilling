# Chapter 7: Consistency, Reliability, Security, and Disaster Recovery

This chapter covers consistency boundaries, reliability patterns, rate limiters, platform security, and disaster recovery.

---

## PART 39: Consistency Boundaries

Large-scale systems trade consistency for availability (CAP theorem). We categorize services by their consistency requirements:

### 1. Strong Consistency (ACID Required)
* **Use Cases**: Payments, account credentials, connection states, and message delivery status.
* **Why**: Financial accounts and security states cannot tolerate double-processing or synchronization lag. If a user rejects a connection request, the database must write this state immediately to block further communications.
* **Implementation**: Relational databases (PostgreSQL) configured with primary locks, write replicas, and active session coordination.

### 2. Eventual Consistency (BASE Model Acceptable)
* **Use Cases**: Like counts, comment counts, video view counts, recommendation feeds, search index sync, and analytics.
* **Why**: If a user likes a post, it does not matter if their followers see the updated counter 2 seconds later. Prioritizing low write latency over immediate accuracy prevents database bottlenecks.
* **Implementation**: Kafka event streams, Redis memory buffers, and asynchronous Elasticsearch batch indexers.

---

## PART 40: Reliability Engineering

To prevent a failure in one service from cascading and causing a cluster outage, implement these design patterns:

* **Circuit Breakers (e.g., Resilience4j)**: Detects elevated error rates on downstream dependencies (e.g., third-party billing APIs). If errors cross a threshold, the breaker trips, returning cached responses or fallback values immediately, protecting resources from exhaustion.
* **Bulkheads**: Allocates dedicated thread pools to separate system operations (e.g., isolate profile edits from feed reads). If the profile edit database runs slow, the profile edit thread pool saturates, but the feed read service remains unaffected.

```text
                  API GATEWAY THREAD POOL (Standard)
                               /       \
                              /         \
                 Bulkhead A Pool        Bulkhead B Pool
                 [Feed Read Threads]    [Profile Edit Threads]
                 * 100 threads max       * 10 threads max
```

* **Graceful Degradation**: If the personalized recommendation engine goes offline, fallback to serving popular posts or cached feeds, maintaining a working system for the user.

---

## PART 43 & 44: Security & Rate Limiting

### 1. Authentication & API Security
* **JWT Token Security**: Clients include a JSON Web Token (JWT) in request headers: `Authorization: Bearer <JWT>`. The API Gateway decrypts the token to verify identity and extract permissions.
* **WAF (Web Application Firewall)**: Sits at the network edge, filtering out SQL injection, cross-site scripting (XSS), and DDoS attacks.
* **Signed Media URLs**: To prevent unauthorized access to private media (e.g., WhatsApp media attachments or private Instagram posts), servers return short-lived pre-signed URLs. The client downloads the file directly from S3, which verifies the URL signature.

---

### 2. Rate Limiting: Redis Token Bucket Design

A rate limiter protects APIs from abuse by limiting the number of requests a user can make within a time window.

```text
 Client API Request -> Fetch Token Bucket State (Redis Hash)
                             |
                     Refill tokens based on time elapsed
                             |
                  Are tokens available (> 0)?
                    /                   \
                 Yes                     No
                 /                         \
       Decrement token count         Reject Request
       Process API Request           Return HTTP 429 Too Many Requests
```

#### Redis Lua Script (Atomic Execution):
```lua
local key = KEYS[1] -- The rate limit key (e.g., rate:1001:login)
local limit = tonumber(ARGV[1]) -- Maximum bucket capacity
local refill_rate = tonumber(ARGV[2]) -- Refill rate per second
local current_time = tonumber(ARGV[3]) -- Epoch timestamp in seconds

local bucket = redis.call('hgetall', key)
local tokens = limit
local last_update = current_time

if #bucket > 0 then
    for i = 1, #bucket, 2 do
        if bucket[i] == 'tokens' then
            tokens = tonumber(bucket[i+1])
        elseif bucket[i] == 'last_update' then
            last_update = tonumber(bucket[i+1])
        end
    end
    -- Calculate tokens to refill based on time elapsed
    local elapsed = current_time - last_update
    tokens = math.min(limit, tokens + (elapsed * refill_rate))
else
    -- Bucket does not exist; initialize
    redis.call('hset', key, 'tokens', tokens, 'last_update', last_update)
end

if tokens >= 1 then
    tokens = tokens - 1
    redis.call('hset', key, 'tokens', tokens, 'last_update', current_time)
    return 1 -- Allowed
else
    return 0 -- Rejected (Rate limited)
end
```

---

## PART 45: Disaster Recovery (DR)

Disaster recovery strategies align with two metrics: **RPO (Recovery Point Objective)** (tolerable data loss window) and **RTO (Recovery Time Objective)** (tolerable system downtime window).

### DR Strategy: Multi-Region Active-Passive Failover

```text
       PRIMARY REGION (us-east-1)                 SECONDARY REGION (us-west-2)
  +----------------------------------+       +----------------------------------+
  | Application Gateway (Active)     |       | Application Gateway (Standby)    |
  | PostgreSQL Master                |       | PostgreSQL Read Replica          |
  | Kafka Cluster (Active)           |       | Kafka Cluster (Standby)          |
  +----------------------------------+       +----------------------------------+
        \                                                 ^
         \---- (Asynchronous DB & Offset Replication) ---/
```

* **Data Replication**: PostgreSQL asynchronously replicates transactions to the standby region (`us-west-2`). Kafka offset mappings are synchronized using MirrorMaker 2.
* **Failover Scenario (Primary Region Outage)**:
  1. The global DNS router (e.g., AWS Route 53) detects the primary region is offline and updates routing targets.
  2. The standby PostgreSQL read replica in `us-west-2` is promoted to master.
  3. Standby application services are scaled up, and clients reconnect to the new region.
  4. **Target Metrics**: RTO $< 5$ minutes, RPO $< 10$ seconds (governed by asynchronous database replication lag).

---

## Quick Revision
* Strong consistency is required for transactional updates; eventual consistency is acceptable for engagement counters and feeds.
* Use thread pool bulkheads and circuit breakers to prevent localized failures from cascading.
* JWTs manage authentication at the API gateway layer; WAFs block malicious traffic at the network edge.
* Lua scripts execution in Redis ensures atomic rate-limiting checks, preventing race conditions.
* Multi-region replication is required to achieve low RTO/RPO disaster recovery targets.

## Common Mistakes
* **Using eventually consistent databases for credentials and transactional state**: This can result in double-processing and security vulnerabilities.
* **Failing to configure timeouts on downstream HTTP requests**: Unbounded request times can block application thread pools, leading to service degradation.
* **Executing rate-limiting checks in SQL databases**: High-volume write updates on rate-limit tables will overload database CPU.
