# Chapter 6: Security, Schema Registry, and Transactions

This chapter covers data governance, security configurations, and transactional processing. We will analyze transport encryption, client authorization, schema evolution rules, and transactional boundaries.

---

## PART 26: Security (TLS, SASL, and ACLs)

Securing a Kafka cluster requires addressing three areas: encryption, authentication, and authorization.

```text
                    CLIENT ENVIRONMENT (Order Service)
                                    |
                    (1) Encrypted Channel (TLS / HTTPS)
                                    |
                                    v
                            SECURITY GATEWAY
              +-------------------------------------------+
              | (2) Authentication (Who are you? SASL)    |
              | (3) Authorization (What can you do? ACL)  |
              +-------------------------------------------+
                                    |
                                    v
                              KAFKA BROKER
```

### 1. Transport Encryption (TLS)
By default, Kafka traffic travels in plaintext. In production, configure **TLS (Transport Layer Security)** to encrypt data in transit between clients and brokers, and for inter-broker communications.

### 2. Authentication (SASL)
Use **SASL (Simple Authentication and Security Layer)** to authenticate clients:
* **SASL/PLAIN**: Simple username/password authentication (suitable only when wrapped inside a TLS-encrypted connection).
* **SASL/SCRAM**: Uses salted challenge-response hashing (e.g., SCRAM-SHA-256) for secure credential exchanges.
* **SASL/GSSAPI (Kerberos)**: Enterprise standard for single sign-on authentication.
* **Mutual TLS (mTLS)**: Uses client certificates to authenticate identity.

### 3. Authorization (Access Control Lists / ACLs)
Once authenticated, clients must be authorized to access resources. Kafka uses ACLs to grant or restrict access to topics, consumer groups, and transactional IDs.

#### Example ACL: Granting permissions to a producer
Grant user `order-service` write permissions to the topic `order-events`:
```bash
docker exec -it kafka-kraft kafka-acls --bootstrap-server localhost:9092 \
  --add --allow-principal User:order-service \
  --operation Write --operation Describe \
  --topic order-events
```

#### Example ACL: Granting permissions to a consumer
Grant user `notification-service` read permissions to the topic `order-events` under a specific consumer group:
```bash
docker exec -it kafka-kraft kafka-acls --bootstrap-server localhost:9092 \
  --add --allow-principal User:notification-service \
  --operation Read --operation Describe \
  --topic order-events \
  --group notification-group
```

---

## PART 27: Schema Management (Schema Registry)

### 1. The Need for Schema Governance
In a microservices architecture, a producer change can break downstream consumers. If a producer removes a field or changes a data type from `Long` to `String`, consumer applications will crash at runtime with deserialization errors.

### 2. Schema Registry Architecture
To prevent breaking changes, place a **Schema Registry** (e.g., Confluent Schema Registry) between producers and consumers.

```text
 Producer                               Schema Registry                             Consumer
    |                                          |                                       |
    | --- 1. Check/Register Schema ----------> |                                       |
    | <-- 2. Return Schema ID (e.g., ID: 5) --- |                                       |
    |                                          |                                       |
    | --- 3. Send Message (ID: 5 + Payload) -----------------------------------------> |
    |                                          |                                       |
    |                                          | <--- 4. Fetch Schema for ID: 5 ------ |
    |                                          | ---> 5. Return Schema Definition ---- |
    |                                          |                                       |
    |                                          |                                       | (6. Deserialize)
```

* Producers register schemas before publishing events.
* The Schema Registry validates that the schema meets compatibility rules.
* Instead of sending the full schema with every message, the producer prefixes the payload with a **5-byte Magic Byte & Schema ID**, keeping payload sizes small.
* The consumer reads the Schema ID from the prefix, fetches the schema definition from the Registry (caching it locally), and deserializes the payload.

### 3. Schema Compatibility Modes

| Compatibility Mode | Description | Producer Upgrade Path | Consumer Upgrade Path |
| :--- | :--- | :--- | :--- |
| **BACKWARD** (Default) | Consumers using the new schema can read data written with the older schema. | Upgrade consumers first. | Can add optional fields, or delete fields. |
| **FORWARD** | Consumers using the older schema can read data written with the new schema. | Upgrade producers first. | Can delete optional fields, or add fields. |
| **FULL** | Schema is both backward and forward compatible. | Upgrade in any order. | Can only add/delete optional fields. |
| **NONE** | Compatibility checks are disabled. | Safe upgrades not guaranteed. | N/A |

#### Evolution Example (Avro)
```avro
// Version 1 Schema
{
  "type": "record",
  "name": "OrderCreatedEvent",
  "namespace": "com.upskill.events",
  "fields": [
    { "name": "orderId", "type": "string" },
    { "name": "amount", "type": "double" }
  ]
}

// Version 2 Schema (Backward Compatible addition of optional field 'currency')
{
  "type": "record",
  "name": "OrderCreatedEvent",
  "namespace": "com.upskill.events",
  "fields": [
    { "name": "orderId", "type": "string" },
    { "name": "amount", "type": "double" },
    { "name": "currency", "type": "string", "default": "USD" } // Default ensures backward compatibility
  ]
}
```

---

## PART 28: Kafka Transactions

### 1. Simple Definition
Kafka Transactions allow a producer to send batches of messages to multiple partitions and topics atomically. Either all messages are successfully written, or none are.

### 2. Technical Explanation: `read_committed` Isolation
When transactional writes are enabled, the consumer's `isolation.level` configuration determines which messages are returned:
* **`read_uncommitted` (Default)**: The consumer polls all records, including aborted or uncommitted transactional messages.
* **`read_committed`**: The consumer only polls messages from non-transactional writes or completed, committed transactions. It filters out aborted transactions using an internal marker partition log.

```text
                  PARTITION LOG WITH TRANSACTIONS
+--------------------+---------------------+--------------------+--------------------+
| Offset 10: Msg A   | Offset 11: Msg B    | Offset 12: Msg C   | Offset 13: Marker  |
| (Committed)        | (Aborted Tx)        | (Active Tx)        | (Commit / Abort)   |
+--------------------+---------------------+--------------------+--------------------+
   ^                                                              ^
   |                                                              |
   * Read Committed Consumer stops reading here until the Commit marker is written.
```

### 3. The Core Limitation: Database Transaction Boundaries
A common architectural mistake is assuming that Kafka Transactions guarantee exactly-once processing when writing to an external database like PostgreSQL.

> [!WARNING]
> **Kafka's Transaction Coordinator has no authority over PostgreSQL.**
> If your application consumes from Kafka, writes to PostgreSQL, and commits the offset to Kafka in a transaction:
> * If the database write succeeds, but the Kafka offset commit fails due to a crash, the transaction will be aborted.
> * However, the database write **cannot be rolled back** because it has already been committed to disk. 
> To achieve end-to-end data integrity across Kafka and external databases, use the Outbox Pattern or implement client-side idempotency.

---

## Quick Revision
* Plaintext traffic is insecure; enforce TLS for in-transit encryption and SASL/SCRAM for client authentication.
* Access Control Lists (ACLs) define principal permissions on topics, consumer groups, and transactions.
* Schema Registries prevent breaking modifications by enforcing schema compatibility checks at the producer level.
* Under `isolation.level=read_committed`, consumers wait for transaction marker blocks before polling messages.
* Kafka transactions do not span external databases; use deduplication keys or the Outbox pattern to preserve consistency across systems.

## Common Mistakes
* **Deploying SASL/PLAIN without TLS encryption**: This exposes cleartext passwords over the network.
* **Deleting fields in a BACKWARD compatibility schema without default values**: This breaks older consumer instances that expect the fields to exist.
* **Configuring transactions without setting a unique `transactional.id` per producer**: This prevents the coordinator from tracking transaction states accurately.

## Production Perspective
When upgrading schemas in production, run compatibility checks within your CI/CD pipeline using maven/gradle plugins before merging code. This identifies schema violations before they reach the Schema Registry in staging or production environments.

## Interview Questions
1. **Explain the difference between BACKWARD and FORWARD schema compatibility.** (Backward compatibility allows newer consumers to read older payloads; Forward compatibility allows older consumers to read newer payloads).
2. **What is the purpose of the 5-byte prefix in Schema Registry serialized payloads?** (The first byte is the Magic Byte; the remaining 4 bytes contain the unique Schema ID from the registry).
3. **What does `read_committed` isolation do?** (It prevents consumers from polling messages belonging to active or aborted transactions until a commit marker is written).
4. **Why can't Kafka transactions coordinate writes to PostgreSQL?** (Because Kafka does not support distributed transaction management (2PC) over external databases).
5. **How does SASL/SCRAM differ from SASL/PLAIN?** (SCRAM hashes challenge-response exchanges, preventing credential exposure over network lines; PLAIN transmits credentials in cleartext).

## Principal Engineer Thinking
When designing multi-tenant clusters:
* Enforce **Topic Name Prefixes** mapping to organizational departments (e.g., `billing.order-events`, `shipping.delivery-events`).
* Use wildcards in ACL definitions (`billing.*`) to grant department-wide permissions while preventing cross-department namespace access.
* Enable quotas (`producer_byte_rate` and `consumer_byte_rate`) on clients to prevent a single misconfigured application from consuming cluster bandwidth.
