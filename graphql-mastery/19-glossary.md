# Module 19: Comprehensive GraphQL & Distributed Systems Glossary

Welcome to Module 19 of the **GraphQL Mastery Guide**. This glossary serves as an authoritative, indexed quick-reference dictionary for all architectural, protocol, Java, Spring, database, security, and federation terminology used throughout this handbook.

---

## A–Z Terminology Index

### A
- **Abstract Syntax Tree (AST):** A hierarchical in-memory tree representation of the source GraphQL document created by lexical analysis and parsing. Used by `graphql-java` for validation, depth limiting, and execution planning. *(See [Module 01](./01-fundamentals.md) and [Module 14](./14-advanced-concepts.md))*
- **Alias:** A client-side query syntax construct (`customName: fieldName`) used to rename fields in the returned JSON object and prevent key collisions when querying the same field multiple times with different arguments. *(See [Module 03](./03-queries.md))*
- **Antlr4:** The parser generator library used by `graphql-java` to tokenize and parse GraphQL query strings into an AST.
- **Apollo Federation:** An enterprise architecture pattern where a unified "Supergraph" is composed of multiple independent "Subgraph" microservices, routed via an edge router. *(See [Module 12](./12-microservices.md))*
- **Argument (`@Argument`):** A named key-value pair passed into a field in a GraphQL query or mutation, mapped in Spring for GraphQL controllers via the `@Argument` annotation. *(See [Module 03](./03-queries.md) and [Module 05](./05-spring-boot-integration.md))*
- **Automated Persisted Queries (APQ):** A performance optimization protocol where clients send a cryptographic hash (SHA-256) of a query over HTTP GET instead of the full query string, enabling edge CDN caching. *(See [Module 11](./11-security-and-performance.md))*

### B
- **Backpressure:** A reactive streams mechanism (provided in Project Reactor by `Flux<T>`) that enables downstream subscribers to signal demand upstream, preventing slow consumers from being overwhelmed by fast WebSocket event streams. *(See [Module 09](./09-subscriptions.md))*
- **BatchMapping (`@BatchMapping`):** Spring for GraphQL annotation that automatically registers a `DataLoader` behind the scenes, resolving relationships for a list of parent entities in a single batched database query. *(See [Module 06](./06-database-and-optimization.md))*
- **BOLA (Broken Object Level Authorization):** Formerly known as IDOR; a security vulnerability where an attacker manipulates an ID argument to access or modify records belonging to other users. *(See [Module 07](./07-security-and-jwt.md))*

### C
- **Coercing (`Coercing<I, O>`):** The core `graphql-java` interface required to define custom scalars, handling serialization, variable parsing, and AST literal parsing. *(See [Module 14](./14-advanced-concepts.md))*
- **Connection (Relay Connection):** The root container in the Relay pagination specification representing a paginated page slice, containing `edges` and `pageInfo`. *(See [Module 08](./08-pagination-filtering-sorting.md))*
- **ContextView:** A reactive dictionary carried across the GraphQL execution lifecycle containing request metadata, HTTP headers, and authentication tokens. *(See [Module 05](./05-spring-boot-integration.md))*
- **Cursor:** An opaque string representing the exact position of a record in an ordered dataset, enabling constant-time $\mathcal{O}(1)$ Keyset pagination. *(See [Module 08](./08-pagination-filtering-sorting.md))*

### D
- **DataFetcher:** The low-level `graphql-java` interface responsible for fetching the data for a specific field in the schema. In Spring for GraphQL, high-level controller methods annotated with `@QueryMapping` or `@SchemaMapping` are automatically adapted into `DataFetcher` instances. *(See [Module 05](./05-spring-boot-integration.md))*
- **DataFetcherResult:** A wrapper object enabling a resolver to return valid domain data while simultaneously appending field-level error messages to the `errors` array. *(See [Module 10](./10-error-handling-validation.md))*
- **DataLoader:** A utility designed by Facebook that queues individual data requests across an execution frame, deduplicates keys, and resolves them via a single batch function, eliminating the N+1 query problem. *(See [Module 06](./06-database-and-optimization.md))*
- **Deprecated (`@deprecated`):** Built-in directive used to mark schema fields or enum values as obsolete, providing a deprecation reason without breaking backward compatibility. *(See [Module 02](./02-schema-and-types.md))*
- **Directive:** A keyword preceded by `@` applied to schema declarations or client queries to alter runtime behavior (e.g., `@include`, `@skip`, `@auth`). *(See [Module 03](./03-queries.md) and [Module 14](./14-advanced-concepts.md))*

### E
- **Edge:** A Relay pagination wrapper linking a connection to a node, containing the `node` object and its opaque `cursor` string. *(See [Module 08](./08-pagination-filtering-sorting.md))*
- **ExecutionStrategy:** The `graphql-java` internal strategy for resolving fields: `AsyncExecutionStrategy` (parallel for queries) and `AsyncSerialExecutionStrategy` (strictly sequential for mutations). *(See [Module 14](./14-advanced-concepts.md))*
- **ExtendedScalars:** A production library (`graphql-java-extended-scalars`) providing pre-built custom scalars such as `DateTime`, `BigDecimal`, `UUID`, and `JSON`. *(See [Module 02](./02-schema-and-types.md))*

### F
- **Field-Level Authorization:** The security practice of evaluating user permissions on individual fields of an object type (e.g., redacting `socialSecurityNumber`), returning `null` for unauthorized callers while delivering sibling fields. *(See [Module 07](./07-security-and-jwt.md))*
- **Fragment:** A reusable selection set in GraphQL used to share common field definitions across multiple queries without duplicating code. *(See [Module 03](./03-queries.md))*

### G
- **GraphiQL:** An in-browser IDE for interactive exploration, auto-completion, testing, and debugging of GraphQL schemas and operations. *(See [Module 05](./05-spring-boot-integration.md))*
- **`graphql-transport-ws`:** The modern, RFC-compliant WebSocket sub-protocol used to stream GraphQL subscriptions, replacing the deprecated `subscriptions-transport-ws`. *(See [Module 09](./09-subscriptions.md))*

### I
- **Idempotency:** The property of an operation where executing it multiple times produces the exact same server state as executing it once. Essential for financial GraphQL mutations. *(See [Module 04](./04-mutations.md))*
- **Incremental Delivery (`@defer`, `@stream`):** A proposed GraphQL specification extension allowing a server to stream slow subgraphs or array chunks over HTTP multipart chunks after delivering the initial fast response. *(See [Module 14](./14-advanced-concepts.md))*
- **Inline Fragment (`... on Type`):** A conditional fragment spread applied directly inside an operation to query fields specific to a concrete type implementing an Interface or Union. *(See [Module 02](./02-schema-and-types.md))*
- **Input Type (`input`):** A specialized GraphQL type restricted to inbound arguments in queries and mutations. Cannot contain object types or circular relations. *(See [Module 02](./02-schema-and-types.md))*
- **Instrumentation:** A `graphql-java` interceptor hook mechanism used for cross-cutting concerns (query depth analysis, complexity limits, tracing, and metrics). *(See [Module 11](./11-security-and-performance.md))*
- **Interface:** An abstract GraphQL type defining a set of common fields that all implementing concrete types must contain. *(See [Module 02](./02-schema-and-types.md))*
- **Introspection:** The capability of a GraphQL server to query its own schema structure via `__schema` and `__type` meta-fields. Must be disabled in production. *(See [Module 11](./11-security-and-performance.md))*

### K
- **Keyset Pagination:** High-performance database pagination that seeks directly to an indexed sort value and primary key (`WHERE created_at < :cursor ORDER BY created_at DESC LIMIT 20`), running in constant $\mathcal{O}(1)$ time. *(See [Module 08](./08-pagination-filtering-sorting.md))*

### M
- **Mutation (`@MutationMapping`):** A root GraphQL operation intended for state modifications (inserts, updates, deletes), guaranteed to execute sequentially. *(See [Module 04](./04-mutations.md))*
- **Mutation Payload Pattern:** An enterprise design pattern where mutations accept a single `input` object and return a dedicated `Payload` object containing the entity, a `success` boolean, and a list of `userErrors`. *(See [Module 04](./04-mutations.md))*

### N
- **N+1 Query Problem:** A performance flaw where fetching a list of $N$ parent objects results in 1 parent query followed by $N$ secondary queries for child data. Solved via `DataLoader` and `@BatchMapping`. *(See [Module 06](./06-database-and-optimization.md))*
- **Node:** The target business entity wrapped inside a Relay `Edge`. *(See [Module 08](./08-pagination-filtering-sorting.md))*
- **Non-Null (`!`):** A type modifier indicating that a field or argument cannot be `null`. If a non-null field resolves to null, null propagation bubbles upward. *(See [Module 02](./02-schema-and-types.md))*

### O
- **Open-In-View (OSIV):** A Spring JPA pattern that holds database connections open across the entire HTTP lifecycle. Must be disabled (`spring.jpa.open-in-view=false`) in GraphQL systems. *(See [Module 05](./05-spring-boot-integration.md))*
- **OperationName:** The name of the specific query or mutation to execute when a document contains multiple operations. *(See [Module 01](./01-fundamentals.md))*
- **Optimistic Locking (`@Version`):** A concurrency control strategy where Hibernate checks a version counter before writing an update, preventing lost updates without holding database locks. *(See [Module 04](./04-mutations.md))*
- **Over-fetching:** The inefficiency in REST APIs where clients receive unnecessary attributes, wasting mobile bandwidth and CPU deserialization. *(See [Module 01](./01-fundamentals.md))*

### P
- **PageInfo:** A Relay pagination metadata object providing `hasNextPage`, `hasPreviousPage`, `startCursor`, and `endCursor`. *(See [Module 08](./08-pagination-filtering-sorting.md))*
- **Partial Response:** The ability of GraphQL to return valid data for successful subgraphs in `data` while returning diagnostic errors in `errors` when an isolated field fails. *(See [Module 10](./10-error-handling-validation.md))*

### Q
- **Query (`@QueryMapping`):** A root GraphQL read-only operation executing concurrently in parallel across root fields. *(See [Module 03](./03-queries.md))*
- **Query Complexity Analysis:** An instrumentation technique that calculates the computational cost of a query by weighting fields and pagination multipliers to block DoS attacks. *(See [Module 11](./11-security-and-performance.md))*
- **Query Depth Limiting:** An instrumentation defense that inspects AST nesting to reject deeply recursive queries before execution. *(See [Module 11](./11-security-and-performance.md))*

### R
- **Relay Connection Specification:** The industry standard specification designed by Facebook for cursor-based pagination, using `Connection`, `Edge`, `Node`, and `PageInfo`. *(See [Module 08](./08-pagination-filtering-sorting.md))*
- **RuntimeWiring:** The `graphql-java` mechanism for binding SDL schema types to Java data fetchers, type resolvers, and directives. *(See [Module 05](./05-spring-boot-integration.md))*

### S
- **Saga Pattern:** A distributed transaction pattern using a sequence of local transactions coordinated via events (Kafka) with compensating actions, replacing 2-Phase Commit in microservices. *(See [Module 12](./12-microservices.md))*
- **Scalar:** A primitive leaf type in GraphQL that resolves directly to a concrete value without child fields (`Int`, `Float`, `String`, `Boolean`, `ID`, or custom scalars). *(See [Module 02](./02-schema-and-types.md))*
- **Schema Definition Language (SDL):** The formal syntax used to define the GraphQL type system. *(See [Module 02](./02-schema-and-types.md))*
- **Schema-First:** The engineering practice of authoring SDL schema files first as an authoritative design contract before implementing Java controller code. *(See [Module 05](./05-spring-boot-integration.md))*
- **Schema Stitching:** A centralized technique for merging multiple independent GraphQL schemas into a single gateway API using manual type extension resolvers. *(See [Module 12](./12-microservices.md))*
- **Selection Set:** The set of fields requested within an operation or type enclosed in curly braces `{ ... }`. *(See [Module 03](./03-queries.md))*
- **Subscription (`@SubscriptionMapping`):** A root operation returning a reactive stream (`Flux<T>`) that pushes real-time events over a persistent WebSocket or SSE transport. *(See [Module 09](./09-subscriptions.md))*

### T
- **Testcontainers:** A Java testing library that runs real Docker containers (e.g., PostgreSQL) during JUnit 5 integration tests. *(See [Module 13](./13-testing-and-deployment.md))*
- **TypeResolver:** An engine interface used to determine the concrete GraphQL object type for an object implementing an Interface or Union. *(See [Module 02](./02-schema-and-types.md))*

### U
- **Under-fetching:** The REST problem where a single endpoint does not deliver enough data, requiring clients to execute sequential dependent requests. *(See [Module 01](./01-fundamentals.md))*
- **Union:** A GraphQL abstract type that represents a choice between multiple concrete object types that share no common fields. *(See [Module 02](./02-schema-and-types.md))*

---

## 📚 Authoritative Official References & Specifications

1. **Official GraphQL Specification (June 2018+):**  
   [https://spec.graphql.org/](https://spec.graphql.org/)
2. **Official GraphQL Learning Portal:**  
   [https://graphql.org/learn/](https://graphql.org/learn/)
3. **Spring for GraphQL Official Reference Documentation:**  
   [https://docs.spring.io/spring-graphql/reference/](https://docs.spring.io/spring-graphql/reference/)
4. **GraphQL Java Engine Core Repository:**  
   [https://github.com/graphql-java/graphql-java](https://github.com/graphql-java/graphql-java)
5. **Relay Cursor Connections Specification:**  
   [https://relay.dev/graphql/connections.htm](https://relay.dev/graphql/connections.htm)
6. **Apollo Federation v2 Specification:**  
   [https://www.apollographql.com/docs/federation/](https://www.apollographql.com/docs/federation/)
7. **GraphQL Over WebSocket Protocol (`graphql-transport-ws`):**  
   [https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md)
