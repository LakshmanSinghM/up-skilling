# Chapter 2: WhatsApp System Design

This chapter covers the system design of an enterprise-grade real-time messaging platform similar to WhatsApp. We will analyze WebSocket connection management, message lifecycle status tracking, and distributed presence detection.

---

## 1. Problem Statement
A real-time messaging system must deliver billions of messages daily with sub-second latency, maintain active socket connections for millions of concurrent users, handle offline message delivery queueing, and track user online/offline status without overloading databases.

---

## 2. Requirements

### Functional Requirements:
* One-to-one text messaging.
* Real-time delivery acknowledgements (Sent $\rightarrow$ Delivered $\rightarrow$ Read Receipts).
* Online/offline presence indicators (Last Seen status).
* Typing indicators.
* Offline message buffering and delivery upon reconnection.
* Group chats with message fan-out and participant receipt tracking.
* End-to-end encryption support.

### Non-Functional Requirements:
* **Low Latency**: Message delivery should complete in under 200ms when both users are online.
* **Ordering Guarantees**: Messages within a conversation must be rendered in chronological order.
* **High Concurrency**: The gateway must support millions of simultaneous TCP/WebSocket connections.
* **Storage Optimization**: Minimize server-side message storage (once a message is delivered and acknowledged, it should be deleted from the server).

---

## 3. Scale Estimation

### Assumptions:
* **Daily Active Users (DAU)**: 500 million.
* **Average Messages/User/Day**: 40 messages.
* **Peak Traffic Multiplier**: 2x average traffic.
* **Average Message Size**: 500 bytes (text, encryption metadata, and headers).
* **Concurrent Connections**: 100 million active users online at peak.

---

### Calculations:

#### 1. Ingestion Traffic (Writes/sec):
* **Daily Message Volume**: 500 million DAU $\times$ 40 messages = 20 billion messages/day.
  $$\text{Average Message Rate} = \frac{20,000,000,000 \text{ messages}}{86,400 \text{ sec}} \approx 231,481 \text{ messages/sec}$$
  $$\text{Peak Message Rate} = 231,481 \times 2 \approx 462,962 \text{ messages/sec}$$

#### 2. Network Bandwidth (Ingress/Egress):
* **Average Network Throughput**:
  $$\text{Throughput} = 231,481 \text{ msg/sec} \times 500 \text{ bytes} \approx 115.7 \text{ MB/sec (925 Mbps)}$$
  $$\text{Peak Throughput} \approx 231.4 \text{ MB/sec (1.85 Gbps)}$$

#### 3. Storage Estimates (Temporary Offline Queue):
Assume $10\%$ of messages are sent to offline users and remain buffered on the server for an average of 2 hours.
* **Daily Offline Volume**: 2 billion messages/day.
  $$\text{Concurrent Offline Messages} = \frac{2,000,000,000 \text{ messages}}{24 \text{ hours}} \times 2 \text{ hours} \approx 166.7 \text{ million messages}$$
  $$\text{Temporary Storage Size} = 166.7 \text{ million} \times 500 \text{ bytes} \approx 83.3 \text{ GB}$$

---

## 4. Real-Time Architecture

```text
                  CLIENT A                                     CLIENT B
                     | (WebSocket)                                | (WebSocket)
                     v                                            v
         +-----------------------+                    +-----------------------+
         | Connection Manager 1  |                    | Connection Manager 2  |
         | (Hosts Socket A)      |                    | (Hosts Socket B)      |
         +-----------------------+                    +-----------------------+
                     |                                            ^
                     | (Publishes Message)                        | (Pushes Message)
                     v                                            |
         +-----------------------+                    +-----------------------+
         |    Message Service    |                    |   Delivery Service    |
         +-----------------------+                    +-----------------------+
                     |                                            ^
                     +------------------> Kafka ------------------+
                                            |
                                            v
                                  +-------------------+
                                  | Offline Database  |
                                  | (NoSQL / Dynamo)  |
                                  +-------------------+
```

---

## 5. Message Lifecycle & Request Flow

We trace a message: `User A -> "Hello" -> User B`.

```text
User A                      ConnManager 1                  Kafka                  ConnManager 2                  User B
  |                              |                           |                         |                           |
  | -- 1. Send("Hello") -------> |                           |                         |                           |
  | <-- 2. ACK(Server Received) -|                           |                         |                           |
  |                              | -- 3. Publish ------------> |                         |                           |
  |                              |                           | -- 4. Route ------------> |                           |
  |                              |                           |                         | -- 5. Push("Hello") ----> |
  |                              |                           |                         | <-- 6. ACK(Delivered) ----|
  |                              |                           | <--- 7. Delivery Event -|                           |
  | <--- 8. Event(Delivered) ----|<-- 9. Route --------------|                         |                           |
```

1. **Client A Sends Message**: Client A writes a JSON payload to its open WebSocket connection with Connection Manager 1 (CM1).
2. **CM1 Acknowledgement**: CM1 returns a `SERVER_RECEIVED` receipt to Client A, updating the UI on Client A to a single gray checkmark ($\checkmark$).
3. **Kafka Routing**: CM1 publishes the message to the Kafka topic `chat-messages`.
4. **Active Delivery (Client B Online)**: The `Delivery Service` polls `chat-messages` and queries the `Routing Service` to locate Client B's active connection. 
   * The `Routing Service` indicates Client B is connected to Connection Manager 2 (CM2).
   * The `Delivery Service` forwards the message to CM2, which pushes the payload over Client B's WebSocket.
5. **Client B Acknowledgement**: Client B's client application receives the message and returns a `CLIENT_DELIVERED` receipt to CM2. 
   * CM2 writes this event to Kafka. The `Delivery Service` routes it back to Client A via CM1, updating the UI to a double gray checkmark ($\checkmark\checkmark$).
6. **Read Receipt**: When User B opens the chat interface, the client sends a `CLIENT_READ` receipt. This follows the same path back to Client A, updating the UI to double blue checkmarks ($\checkmark\checkmark$).

---

## 6. Core Services
* **WebSocket Connection Manager**: Stateful service hosting TCP/WebSocket connections, managing heartbeats, and handling socket terminations.
* **Routing Service**: Stores active mappings of `userId -> connectionManagerId` in a Redis cache.
* **Message Service**: Validates payloads, processes headers, and writes messages to the Kafka ingestion pipeline.
* **Delivery Service**: Reads messages from Kafka and coordinates push delivery to online users.
* **Presence Service**: Tracks online status and manages "Last Seen" timestamps.

---

## 7. Database Design (Cassandra for Offline Buffer)

### Why not use SQL?
SQL databases are not optimized to handle the high-throughput write/delete patterns of real-time messaging. Cassandra's log-structured merge-tree (LSM) engine supports fast, sequential writes.

### Cassandra Schema: `offline_messages`
```sql
CREATE KEYSPACE whatsapp_offline WITH replication = {
    'class': 'NetworkTopologyStrategy', 
    'replication_factor': 3
};

CREATE TABLE whatsapp_offline.messages (
    recipient_id bigint,
    message_id uuid,
    sender_id bigint,
    encrypted_payload blob,
    created_at timestamp,
    PRIMARY KEY (recipient_id, message_id)
) WITH CLUSTERING ORDER BY (message_id ASC);
```
* **Partition Key**: `recipient_id`. This groups all undelivered messages for a user on the same database node, allowing them to be retrieved in a single sequential read when the user reconnects.

---

## 8. Offline Messaging Strategy

```text
                             MESSAGE INGESTED
                                    |
                    Is Recipient Online (Redis)?
                                 /      \
                              Yes        No
                              /            \
          [Online Flow]                    [Offline Flow]
          Route directly to CM             - Write to Cassandra Table
                                           - Publish Push Notification (APNs)
                                           - Delete from DB upon Client Sync ACK
```

1. If the recipient is offline, the `Delivery Service` writes the encrypted payload to the `offline_messages` table.
2. The service then publishes a notification request event to Apple Push Notification Service (APNs) or Google Cloud Messaging (GCM) to alert the user.
3. When the recipient reconnects and establishes a WebSocket connection:
   * The Connection Manager queries Cassandra: `SELECT * FROM messages WHERE recipient_id = ?`.
   * It pushes the buffered messages to the user in order.
   * Once the client acknowledges receipt of the messages, the Connection Manager deletes the records from the Cassandra table to free storage.

---

## 9. Conversation-Level Ordering

### The Challenge:
Due to network lag and retries, Message B might arrive at the server before Message A, rendering them out of order on the recipient's device.

### The Principal Engineer Solution:
* **Do not rely on server-side global timestamps.** 
* **Client-Side Monotonic Sequencing**: Every client device maintains a local counter for each chat session. When User A sends a message, it includes a incrementing counter: `seqId`.
* **Ordering Logic**:
  * Recipient Client receives `seqId=5`.
  * If it is currently rendering `seqId=3`, it buffers `seqId=5` and waits for `seqId=4` to arrive before rendering both, ensuring the conversation order remains intact.
* **Kafka Partitioning**: To maintain write ordering, route messages using the `conversationId` (e.g., `hash(userId1, userId2)`) as the partition key.

---

## 10. Presence Service (Redis Heartbeats)

* **Why not use PostgreSQL for heartbeat tracking?** Writing user heartbeat updates to a relational database every 5 seconds would saturate write capacity.
* **Redis TTL Keys**: When a client is online, it sends a heartbeat ping every 5 seconds.
  * The `Presence Service` writes a Redis key: `SET presence:{userId} online EX 15`.
  * If the user disconnects or crashes, the key expires after 15 seconds.
  * To check if a user is online, run: `GET presence:{userId}`. If null, the user is offline, and the service falls back to reading the `last_seen` timestamp from Cassandra.

---

## 11. WhatsApp Conceptual End-to-End Encryption

Conceptually, WhatsApp uses the Signal Protocol to encrypt messages. The server acts only as a directory for public keys and a transport channel for encrypted payloads.

```text
 Sender                                    Server                                  Receiver
   |                                         |                                        |
   | --- 1. Fetch Public Identity Key -----> |                                        |
   | <-- 2. Return Receiver's Key ---------- |                                        |
   |                                         |                                        |
   | (Generates temporary session key,       |                                        |
   |  encrypts message payload)              |                                        |
   |                                         |                                        |
   | --- 3. Send Encrypted Message payload -----------------------------------------> |
   |                                         |                                        |
   |                                         |                                        | (Decrypts using 
   |                                         |                                        |  private key)
```

* **No Plaintext on Servers**: The server cannot decrypt the message payload because it does not have access to the recipient's private key.

---

## 12. Trade-offs: WebSockets vs Long Polling

### Long Polling:
* **Pros**: Simple to scale; requests use standard HTTP/HTTPS channels and fit easily within existing load balancing topologies.
* **Cons**: Introduces high CPU overhead because clients repeatedly establish HTTP connections, degrading real-time performance.

### WebSockets (Recommended):
* **Pros**: Low overhead; establishes a single, persistent TCP connection for bidirectional, real-time message delivery.
* **Cons**: Requires stateful connection management. Connection servers must maintain open socket file descriptors, requiring custom scaling strategies.

---

## 13. Interview / System Design Questions (WhatsApp-focused)

### Q1: How do you scale a WebSocket gateway to support 10 million concurrent connections?
* **Expected Thinking**: Detail host limits (file descriptors and memory capacity).
* **Strong Answer**: Each open socket connection consumes a file descriptor. We must increase the OS limits (`ulimit -n 1000000`). We also tune the TCP socket buffer sizes (`sysctl net.ipv4.tcp_rmem` and `tcp_wmem`) down to 4KB per connection. This reduces connection memory usage, allowing a 64GB RAM instance to support up to 1 million active socket connections. We scale the gateway by deploying a pool of 10-15 connection manager instances behind an Layer 4 Load Balancer (TCP routing).
* **Common Mistake**: "Use a larger HTTP load balancer to round-robin connections."
* **Follow-up**: "What happens if a Connection Manager instance crashes?" (The clients reconnect automatically, triggering a reconnect storm. We use exponential backoff and jitter on client reconnection loops to distribute the connection spike).

---

## 14. Complete System Design Exercises

### Exercise 2: Design WhatsApp Messaging
* **Requirements**: Deliver encrypted messages to 500M DAUs under 200ms, supporting offline buffering.
* **Reference Solution**: Connects clients via WebSockets to stateful Connection Managers. The CMs route incoming messages through Kafka. The `Delivery Service` queries a Redis-backed Routing Service to push messages to online users, or routes them to Cassandra offline tables if recipients are offline.
