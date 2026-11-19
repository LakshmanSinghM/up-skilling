# Module 16: 100 Enterprise GraphQL Interview Questions & In-Depth Answers

Welcome to Module 16 of the **GraphQL Mastery Guide**. This is an exhaustive, production-grade interview handbook containing **100 technical questions with detailed architectural answers**, designed for **Senior Java Backend Engineers, Tech Leads, and Software Architects**.

---

## PART 1: Core Fundamentals & Architectural Concepts (Questions 1–15)

### Q01: What is GraphQL and what fundamental problem does it solve in modern distributed systems?
**Answer:** GraphQL is an open-source query and data manipulation language for APIs, as well as a runtime execution engine for fulfilling those queries against existing data stores. It was designed to address the inherent rigidity of REST APIs in mobile and multi-client environments. In REST, endpoints are server-defined resources that frequently result in **over-fetching** (delivering unneeded fields over constrained mobile networks) or **under-fetching** (requiring clients to chain multiple sequential HTTP requests, creating network latency waterfalls). GraphQL exposes a unified, strongly typed schema, allowing clients to declare the exact data shape required in a single HTTP round-trip.

### Q02: How does the network lifecycle of a GraphQL request differ from a standard REST request?
**Answer:** REST uses standard HTTP semantics: requests are distributed across multiple distinct URIs (`/orders`, `/users`, `/products`) using semantic verbs (`GET`, `POST`, `PUT`, `DELETE`), and leverage native HTTP caching via `ETag` and `Cache-Control`. GraphQL routes virtually all operations (queries and mutations) through a single endpoint (typically `POST /graphql`) with a JSON payload containing `query`, `variables`, and `operationName`. The response is wrapped in a standardized envelope containing `data` and `errors`, almost universally returning an HTTP `200 OK` status code even when field-level errors occur.

### Q03: Why is GraphQL considered a "client-driven" API architecture?
**Answer:** In REST, the server team dictates the shape of the payload returned by each endpoint; if a frontend needs a new field, a backend engineer must modify the DTO or create a new endpoint version. In GraphQL, the server publishes an interconnected graph contract (SDL), and the client dictates the response structure. The client can request a subset of fields, traverse nested relations, or combine multiple domain entities in one call without requiring backend code changes.

### Q04: What are the three root operation types in GraphQL and their execution guarantees?
**Answer:**
1. **`Query`:** Read-only operations. Equivalent to HTTP `GET`. Root fields are executed **concurrently in parallel** using asynchronous worker threads (`CompletableFuture`).
2. **`Mutation`:** State-modifying operations (create, update, delete). Root fields are guaranteed to execute **strictly sequentially** in the declared order to prevent race conditions.
3. **`Subscription`:** Real-time push streams over persistent transports (WebSocket or SSE) returning an asynchronous `Publisher<T>` (Project Reactor `Flux<T>`).

### Q05: Why does GraphQL almost always return HTTP 200 OK, even when errors occur?
**Answer:** GraphQL is an application-level protocol decoupled from the underlying transport. A single GraphQL query can request multiple independent subgraphs (e.g., `user` and `orders`). If `user` succeeds but `orders` fails because the database timed out, the request as a whole has not failed. Returning a `500 Internal Server Error` would discard the valid `user` data. By returning `200 OK`, the server delivers partial data in the `data` field alongside detailed diagnostics in the `errors` array.

### Q06: What is the single endpoint concept and what are its pros and cons?
**Answer:**
- **Pros:** Simplifies client routing (one URL for all operations); eliminates endpoint proliferation (`/v1`, `/v2`, `/summary`); allows atomic retrieval of multiple entities in one round-trip.
- **Cons:** Defeats standard HTTP caching proxies (Varnish, Squid, Akamai) that rely on URL paths and GET methods; complicates URL-based firewall and rate-limiting rules; requires application-level query depth/complexity security inspection.

### Q07: How does GraphQL handle API versioning compared to REST?
**Answer:** REST relies on URL path versioning (`/api/v1/users`, `/api/v2/users`) or header versioning, which creates duplicate controller logic and maintenance burdens. GraphQL practices **Continuous Schema Evolution**: fields are never deleted abruptly; instead, old fields are decorated with the `@deprecated(reason: "Use newField")` directive. Tooling hides deprecated fields from auto-completion, and server telemetry tracks field usage until traffic drops to zero, allowing safe removal.

### Q08: What is Over-fetching and how does GraphQL eliminate it?
**Answer:** Over-fetching occurs when an endpoint returns more data attributes than the client needs to render a view (e.g., an endpoint returning 70 user attributes when a dropdown only requires `id` and `name`). GraphQL eliminates this by requiring clients to explicitly declare their selection set; unrequested fields are omitted by the execution engine, reducing JSON serialization CPU overhead and network payload size.

### Q09: What is Under-fetching and the "Waterfall Network" problem?
**Answer:** Under-fetching occurs when a single endpoint does not return sufficient data to render a screen, forcing the client to issue consecutive dependent HTTP calls (e.g., fetch order $\rightarrow$ fetch customer by `order.customerId` $\rightarrow$ fetch payment by `order.paymentId`). On mobile connections with 150ms latency, this causes a multi-second "waterfall" of loading spinners. GraphQL allows clients to fetch the order, customer, and payment in a single request.

### Q10: When should an enterprise NOT choose GraphQL?
**Answer:**
1. **High-throughput internal service-to-service communication:** gRPC over HTTP/2 provides binary serialization that is 5–10x faster with less CPU overhead.
2. **Binary file uploads and streaming:** Large file uploads (videos, images) are poorly suited for GraphQL JSON envelopes; dedicated multipart REST endpoints or S3 pre-signed URLs are superior.
3. **Simple CRUD applications with low client variation:** If an application has simple resources and single web client, REST with OpenAPI is faster to implement without introducing GraphQL complexity.

### Q11: Explain the role of Abstract Syntax Trees (AST) in GraphQL.
**Answer:** When an incoming query string arrives, the engine uses an Antlr4 grammar to parse the string into an Abstract Syntax Tree (AST). The AST represents query operations, selection sets, field arguments, and fragments as a tree of Java objects (`Document`, `OperationDefinition`, `Field`). All static validation, security depth analysis, and execution planning are performed directly on this AST before any data fetcher is invoked.

### Q12: What is the GraphQL Schema Definition Language (SDL)?
**Answer:** SDL is the vendor-neutral, human-readable language used to define the type system of a GraphQL API. It defines object types, scalars, inputs, enums, interfaces, unions, and directives. It serves as the formal contract between frontend and backend teams.

### Q13: What is the GraphQL Foundation and who governs the specification?
**Answer:** Originally developed by Facebook in 2012 and open-sourced in 2015, GraphQL was transferred in 2018 to the **GraphQL Foundation**, a vendor-neutral non-profit hosted by the Linux Foundation. Governance is managed collaboratively by open-source contributors, Apollo, AWS, Meta, IBM, and Microsoft through an RFC process.

### Q14: How does GraphQL affect client-side mobile battery life?
**Answer:** By eliminating over-fetching, payloads are 50–90% smaller, requiring less cellular radio transmission time. By eliminating under-fetching (network waterfalls), the mobile device keeps its cellular antenna powered on for significantly shorter durations, preserving battery life and reducing memory consumption during JSON deserialization.

### Q15: What is the difference between a Document, an Operation, and a Selection Set?
**Answer:**
- **Document:** The complete string or file submitted by the client, which can contain multiple operation definitions and fragments.
- **Operation:** A specific executable block within a document (`query`, `mutation`, or `subscription`), usually named (e.g., `query GetUser`).
- **Selection Set:** The nested set of fields requested within an operation or type (enclosed in curly braces `{ ... }`).

---

## PART 2: Schema Design, SDL & Type System (Questions 16–30)

### Q16: What are the 5 built-in scalar types in GraphQL?
**Answer:** `Int` (signed 32-bit integer), `Float` (signed double-precision IEEE 754), `String` (UTF-8 character sequence), `Boolean` (`true` or `false`), and `ID` (unique identifier serialized as a String, signaling to client caches that it acts as a primary key).

### Q17: Why is `Float` dangerous for financial calculations, and how do you solve it in Java?
**Answer:** `Float` suffers from IEEE 754 binary floating-point rounding errors (e.g., `0.1 + 0.2 = 0.30000000000000004`). In enterprise financial systems, currency must be represented using exact arbitrary-precision decimals. In GraphQL, this is solved by registering a custom scalar `scalar BigDecimal` wired to `graphql.scalars.ExtendedScalars.GraphQLBigDecimal` which serializes directly to Java's `java.math.BigDecimal`.

### Q18: What is the difference between an Object Type and an Input Type?
**Answer:**
- **Object Types (`type`):** Represent outbound data returned from the server to the client. Can contain complex nested relations, interfaces, unions, and circular references.
- **Input Types (`input`):** Represent inbound data passed as arguments from client to server. Must contain only scalar values, enums, or other input types. They can never contain object types, interfaces, or circular dependencies.

### Q19: What is the difference between a GraphQL Interface and a Union?
**Answer:**
- **Interface:** An abstract contract containing a set of common fields that all implementing types must define (e.g., `interface Node { id: ID! }`). Clients can query common fields directly.
- **Union:** A type formed by grouping multiple concrete Object types that share **no common fields** (e.g., `union SearchResult = User | Order | Product`). Clients must use inline fragments (`... on User`) to access fields on member types.

### Q20: Explain the 4 permutations of list and non-null types: `[String]`, `[String!]`, `[String]!`, and `[String!]!`.
**Answer:**
1. `[String]`: The list itself can be null; elements inside can be null (`null`, `[]`, `["A", null]`).
2. `[String!]`: The list can be null; elements inside cannot be null (`null`, `[]`, `["A", "B"]`).
3. `[String]!`: The list cannot be null; elements inside can be null (`[]`, `["A", null]`).
4. `[String!]!`: Neither the list nor its elements can be null (`[]`, `["A", "B"]`). This is the enterprise production standard for collections.

### Q21: What is GraphQL Null Propagation (Error Bubbling)?
**Answer:** If a resolver returns `null` (or throws an unhandled exception) for a field marked with `!` (Non-Null), the GraphQL engine cannot legally return null for that field. To preserve schema contract validity, the engine bubbles the null value up to the parent field. If the parent is also non-null, the null continues bubbling up the AST tree until it reaches a nullable ancestor or the root operation, potentially wiping out valid sibling data.

### Q22: What are the architectural rules for safe vs. breaking schema changes?
**Answer:**
- **Safe:** Adding a new field to an object type; adding a new nullable argument to an existing field; adding a new value to an input enum (with caution).
- **Breaking:** Deleting a field; renaming a field; changing a field from nullable to non-null (`String` $\rightarrow$ `String!`); adding a required (non-null) argument to an existing field; removing an enum value.

### Q23: How do you implement a custom scalar in Spring Boot 3.x using `graphql-java`?
**Answer:** Implement the `graphql.schema.Coercing<I, O>` interface providing implementations for `serialize()` (Java to JSON), `parseValue()` (JSON variable to Java), and `parseLiteral()` (AST node to Java). Then register the resulting `GraphQLScalarType` via a `RuntimeWiringConfigurer` bean injected into the Spring context.

### Q24: What is the `__typename` meta-field and why is it essential for client caches?
**Answer:** `__typename` is an automated field available on every GraphQL object type returning the name of the concrete schema type as a String (e.g., `"Order"`). Client caches (Apollo, Relay) combine `__typename` with the `id` field to create a globally unique cache key (`"Order:101"`), enabling automatic normalized cache updates.

### Q25: Why should you avoid generic "wrapper" response types in GraphQL SDL?
**Answer:** In REST, developers often wrap responses in generic envelopes like `{ status: 200, data: {...}, timestamp: 12345 }`. In GraphQL, this is an anti-pattern because the GraphQL protocol already provides standard `data` and `errors` envelopes. Adding artificial wrappers clutters the schema and impairs client caching.

### Q26: What is the purpose of Schema Directives?
**Answer:** Directives provide a way to describe alternate runtime execution behaviors or annotate schema types. Built-in client directives include `@include(if: Boolean)` and `@skip(if: Boolean)`. Custom server directives (e.g., `@auth(role: ADMIN)`, `@uppercase`, `@cacheControl(maxAge: 60)`) can be wired via `SchemaDirectiveWiring` to enforce authorization or transform data dynamically.

### Q27: How does Spring Boot validate that SDL files match Java controller methods at startup?
**Answer:** When `spring.graphql.schema.inspection.enabled=true` is set, Spring Boot inspects the parsed `GraphQLSchema` against the application context at startup. If any schema field lacks either a corresponding `@SchemaMapping` controller method or a property accessor on the parent Java class, Spring throws a `SchemaProblemException` and halts application boot.

### Q28: What is Schema-First vs. Code-First design, and why does Spring for GraphQL mandate Schema-First?
**Answer:**
- **Code-First:** The schema is automatically generated by inspecting Java annotations on classes (e.g., SPQR or old Netflix DGS). It often leaks internal database/JPA models into the API.
- **Schema-First:** Developers author `.graphqls` files as an authoritative design contract first, then implement controllers matching the schema. Spring for GraphQL adopts Schema-First to enforce clean boundary separation between API contracts and internal implementation details.

### Q29: How do you handle file uploads in a GraphQL schema?
**Answer:** File uploads are not natively defined in the GraphQL specification. Solutions include:
1. **Pre-signed URLs (Recommended):** The client executes a GraphQL mutation `generateUploadUrl(fileName: String!)`, receives an S3/GCS pre-signed PUT URL, and uploads the binary file directly to storage via HTTP PUT.
2. **GraphQL Multipart Request Spec:** Using multipart/form-data with custom scalar `Upload`, though this adds complexity and is non-standard.

### Q30: How do you organize SDL files in a large enterprise Spring Boot repository?
**Answer:** Split schemas modularly across `src/main/resources/graphql/` by domain feature (e.g., `user/user.graphqls`, `order/order.graphqls`). Define the base `type Query` and `type Mutation` in a root file, and extend them in sub-files using `extend type Query { ... }` and `extend type Mutation { ... }`.

---

## PART 3: Queries, Mutations & Operations (Questions 31–45)

### Q31: What are Aliases and when are they required?
**Answer:** Aliases allow a client to rename a field in the response JSON (`customName: fieldName`). They are required when querying the same field multiple times with different arguments in a single query (e.g., `firstOrder: order(id: "1")`, `secondOrder: order(id: "2")`) to prevent JSON key collisions.

### Q32: What is the difference between Named Fragments and Inline Fragments?
**Answer:**
- **Named Fragments (`fragment Name on Type`):** Reusable field sets declared outside an operation and included using the spread operator (`...Name`). Used to eliminate duplicate code across queries.
- **Inline Fragments (`... on ConcreteType`):** Conditional field selections declared directly inside a query on an Interface or Union to access type-specific fields based on the runtime type.

### Q33: Why should GraphQL queries always use Variables instead of string interpolation?
**Answer:**
1. **Security:** String concatenation exposes the API to GraphQL Injection attacks.
2. **Performance:** With variables, the query structure is static. The GraphQL engine parses and validates the AST once and caches it in memory. Variable values are coerced at runtime without re-parsing the AST.

### Q34: How does the GraphQL engine guarantee that mutations do not cause race conditions?
**Answer:** The GraphQL specification mandates that root fields in a `Mutation` operation must be executed **serially** in the exact order specified by the client. The engine waits for the `CompletableFuture` of mutation field 1 to complete before initiating mutation field 2.

### Q35: What is the Mutation Payload Design Pattern?
**Answer:** Rather than returning the modified domain entity directly from a mutation (`updateUser(...): User`), the mutation accepts a single `input` object and returns a dedicated `Payload` object (`updateUser(input: UpdateUserInput!): UpdateUserPayload!`). The payload contains the entity, a `success` boolean, and a list of `userErrors: [UserError!]!`.

### Q36: Why should client mutations include a `clientMutationId`?
**Answer:** In Relay-compliant schemas, mutations accept a `clientMutationId: String` in the input and return it in the payload. This enables frontend clients to correlate asynchronous responses with optimistic UI updates and deduplicate responses.

### Q37: How do directives `@include` and `@skip` optimize backend database queries?
**Answer:** Directives are evaluated during AST execution planning. If an `@include(if: false)` directive is present on a relation field (e.g., `customer @include(if: $fetchCustomer)`), the engine strips the field from the execution plan. The associated `@SchemaMapping` resolver is never called, eliminating the database query.

### Q38: Can a single GraphQL operation contain both a query and a mutation?
**Answer:** No. An operation definition in a GraphQL document must specify exactly one root operation type (`query`, `mutation`, or `subscription`). However, a single document may contain multiple operation definitions, provided each has a unique operation name; the client specifies which one to execute via the `operationName` parameter.

### Q39: What is the difference between `@QueryMapping` and `@SchemaMapping` in Spring for GraphQL?
**Answer:** `@SchemaMapping` is the core annotation that maps a method to any field on any type in the schema (e.g., `@SchemaMapping(typeName = "Order", field = "customer")`). `@QueryMapping` is a meta-annotation shortcut for `@SchemaMapping(typeName = "Query")`.

### Q40: What parameter represents the parent object in a `@SchemaMapping` resolver?
**Answer:** The source (parent) object is passed as the first parameter of the method without any annotation, matching the parent type (e.g., `public CustomerDto customer(OrderDto order)`). Spring inspects the parameter type to bind the parent entity.

### Q41: How do you access GraphQL query arguments in a Spring controller?
**Answer:** Use the `@Argument` annotation on the controller method parameter (e.g., `public OrderDto orderById(@Argument Long id)`). If the Java parameter name differs from the SDL argument name, specify the name explicitly: `@Argument("order_id") Long id`.

### Q42: How does Spring for GraphQL convert GraphQL scalar arguments to Java types?
**Answer:** Spring uses its built-in `ConversionService` and registered Spring formatters/converters to convert incoming GraphQL argument values (Strings, Integers, Maps) into target Java types (e.g., converting an `ID` String into a `UUID` or `Long`, or an input map into a Java Record).

### Q43: How do you validate GraphQL mutation arguments in Spring Boot?
**Answer:** Place Jakarta validation annotations (`@NotNull`, `@Size`, `@Pattern`) on the fields of your Java Input Record, and annotate the method parameter with `@Valid`:
```java
@MutationMapping
public CustomerPayload createCustomer(@Argument @Valid CreateCustomerInput input) { ... }
```

### Q44: What happens if an unhandled runtime exception is thrown inside a resolver?
**Answer:** The GraphQL engine intercepts the exception, sets the field's data value to `null`, and appends an error to the `errors` array. If the field is non-null (`!`), null propagation bubbles up to the parent. In Spring Boot, custom handling is configured via `@GraphQlExceptionHandler`.

### Q45: Can GraphQL queries be executed via HTTP GET requests?
**Answer:** Yes. Although POST is standard, queries can be executed via `GET /graphql?query={order(id:1){total}}&variables={...}`. This is primarily used for **Automated Persisted Queries (APQ)** to leverage CDN caching. Mutations should never be allowed over GET.

---

## PART 4: Spring Boot 3.x & Spring for GraphQL Integration (Questions 46–60)

### Q46: What is the relationship between `graphql-java` and Spring for GraphQL?
**Answer:** `graphql-java` is the low-level, transport-agnostic execution engine that handles AST parsing, validation, and execution. **Spring for GraphQL** is the official Spring framework module that sits on top of `graphql-java`, providing Spring Boot auto-configuration, annotation-driven controllers (`@QueryMapping`, `@BatchMapping`), WebMVC/WebFlux HTTP handlers, WebSocket support, and Spring Security integration.

### Q47: What happens when `spring.jpa.open-in-view` is left enabled in a GraphQL app?
**Answer:** Leaving OSIV enabled keeps database connections open throughout the entire GraphQL execution pipeline. Because GraphQL field resolvers may execute asynchronously across different thread pools or involve long network calls, OSIV holds database connections idle for extended durations, leading to connection pool exhaustion and masking lazy loading bugs that should be solved with batching. OSIV must be disabled (`spring.jpa.open-in-view=false`).

### Q48: Why should you return Java Records / DTOs from resolvers rather than JPA Entities?
**Answer:**
1. **Prevents `LazyInitializationException`:** Accessing un-fetched relations on detached entities outside a transaction causes runtime crashes.
2. **Prevents Accidental Lazy Loading:** Returning JPA entities directly allows reflection to inadvertently trigger secondary SQL queries for unrequested relations.
3. **Decoupling:** Isolates internal database schema changes from the external GraphQL contract.

### Q49: What is `RuntimeWiringConfigurer` and what is it used for?
**Answer:** It is a Spring functional interface used to customize the `graphql-java` `RuntimeWiring.Builder`. It is used to register custom scalars, register `TypeResolver` implementations for Interfaces/Unions, and wire custom schema directives.

### Q50: How do you configure GraphiQL in Spring Boot 3.x?
**Answer:** Enable it in `application.yml`:
```yaml
spring:
  graphql:
    graphiql:
      enabled: true
      path: /graphiql
```
It should always be set to `false` in production environments.

### Q51: How does Spring for GraphQL support reactive programming?
**Answer:** Spring for GraphQL has native support for Project Reactor. Controller methods can return `Mono<T>` for asynchronous single-value resolution, or `Flux<T>` for real-time subscription streaming, seamlessly integrating with Spring WebFlux and R2DBC.

### Q52: What is the `ContextView` in Spring for GraphQL?
**Answer:** `ContextView` is a reactive, read-only dictionary passed throughout the execution of a GraphQL request. It carries HTTP headers, authentication details, correlation IDs, and request metadata, accessible in any resolver via `DataFetchingEnvironment.getGraphQlContext()`.

### Q53: How do you register a custom `DataFetcherExceptionResolver` in Spring Boot?
**Answer:** Extend `DataFetcherExceptionResolverAdapter` and register it as a `@Component` bean, or annotate methods inside a `@ControllerAdvice` class with `@GraphQlExceptionHandler`.

### Q54: What is the purpose of `@BatchMapping` in Spring for GraphQL?
**Answer:** `@BatchMapping` is an annotation that simplifies batch loading to eliminate the N+1 query problem. It automatically registers a `DataLoader` behind the scenes, allowing a resolver method to receive a `List<Parent>` and return a `Map<Parent, Child>` or `List<Child>` in a single batch operation.

### Q55: What is the difference between returning `Map<K, V>` vs `List<V>` from a `@BatchMapping` method?
**Answer:**
- `Map<K, V>`: Explicitly pairs each parent object with its resolved child. Highly recommended for production because it is immune to ordering discrepancies.
- `List<V>`: Must return a list of the exact same size and order as the input list. If a child is missing or the database returns rows out of order, results become mismatched.

### Q56: How do you pass HTTP headers from the request into a GraphQL controller?
**Answer:** Inject the `@Header` annotation onto your controller method parameter:
```java
@QueryMapping
public UserDto currentUser(@Header("X-Tenant-ID") String tenantId) { ... }
```

### Q57: How does Spring for GraphQL handle JSON serialization and deserialization?
**Answer:** It utilizes Spring Boot's default Jackson `ObjectMapper`. You can customize serialization by configuring Jackson modules or registering custom serializers for specific domain types.

### Q58: Can a `@Controller` class contain both REST `@GetMapping` and GraphQL `@QueryMapping` annotations?
**Answer:** Yes, but it is an architectural anti-pattern. Presentation layers should be segregated: maintain dedicated REST controllers and separate GraphQL controllers to preserve clean boundary separation and prevent configuration conflicts.

### Q59: What is `DataFetchingEnvironment` and what information does it provide?
**Answer:** It is the core `graphql-java` context object passed to resolvers. It provides:
- The `source` (parent object).
- The `arguments` map.
- The `selectionSet` requested by the client.
- The `graphQlContext` containing security and HTTP session info.
- The `executionStepInfo` detailing the current AST node coordinates.

### Q60: How do you customize the `ExecutionInput` before query execution in Spring?
**Answer:** Implement a `WebGraphQlInterceptor` bean. Its `intercept` method intercepts incoming HTTP GraphQL requests, allowing inspection or modification of the `ExecutionInput` (e.g., injecting custom context attributes or logging query strings).

---

## PART 5: Database Integration, Hibernate & N+1 Optimization (Questions 61–75)

### Q61: What is the N+1 problem in GraphQL and why does it occur?
**Answer:** The N+1 problem occurs when fetching a list of $N$ parent entities triggers 1 initial database query, followed by $N$ separate queries to fetch relational child data for each individual parent. It occurs in GraphQL because resolvers are modular and field-centric: each child resolver executes independently for each parent instance without knowing that sibling instances are also requesting the same relation.

### Q62: How does `DataLoader` solve the N+1 problem?
**Answer:** `DataLoader` decouples the request for data from the actual execution. Instead of querying the database immediately, a resolver calls `dataLoader.load(id)`, which enqueues the ID and returns a `CompletableFuture`. Once the engine finishes evaluating the current tree level, `DataLoader` deduplicates all queued IDs and invokes a batch loading function that executes a single SQL query: `SELECT * FROM table WHERE id IN (?, ?, ?)`.

### Q63: What is the lifecycle and scope of a `DataLoader` instance?
**Answer:** A `DataLoader` instance **must be request-scoped**. It maintains an in-memory cache of resolved promises. If a `DataLoader` were shared globally across requests, clients would receive stale data, and users would risk seeing cached cross-tenant data, creating severe security vulnerabilities.

### Q64: Why is `FetchType.EAGER` considered an anti-pattern in GraphQL JPA entities?
**Answer:** `FetchType.EAGER` forces Hibernate to fetch relational tables (via JOIN or secondary queries) every time the parent entity is loaded, regardless of whether the GraphQL selection set requested those fields. This eliminates GraphQL's primary performance advantage (fetching only requested fields) and degrades database performance.

### Q65: How do you batch load a One-to-Many relationship (e.g., Order to LineItems) using `@BatchMapping`?
**Answer:** Execute a repository query with `WHERE order_id IN (:orderIds)`. In the `@BatchMapping` method, group the resulting items by their parent `orderId` using Java Streams:
```java
@BatchMapping(typeName = "Order", field = "lineItems")
public Map<OrderDto, List<LineItemDto>> lineItems(List<OrderDto> orders) {
    List<Long> orderIds = orders.stream().map(OrderDto::id).toList();
    List<LineItemEntity> items = repository.findByOrderIdIn(orderIds);
    Map<Long, List<LineItemDto>> grouped = items.stream()
        .collect(Collectors.groupingBy(i -> i.getOrder().getId(), Collectors.mapping(this::toDto, Collectors.toList())));
    return orders.stream().collect(Collectors.toMap(o -> o, o -> grouped.getOrDefault(o.id(), List.of())));
}
```

### Q66: What is a Hibernate `@EntityGraph` and how is it used with GraphQL?
**Answer:** An `@EntityGraph` defines an ad-hoc fetch plan that overrides lazy loading for specific queries, forcing Hibernate to load specified attributes via SQL `LEFT OUTER JOIN`. It is used in root queries where specific relations are guaranteed to be required, retrieving the parent and children in a single SQL round-trip.

### Q67: What is Keyset Cursor Pagination and why is it superior to Offset Pagination?
**Answer:**
- **Offset (`LIMIT 20 OFFSET 50000`):** Requires the database to scan and discard 50,000 index rows, degrading linearly $\mathcal{O}(N)$.
- **Keyset (`WHERE (created_at, id) < (:cursorTime, :cursorId) ORDER BY created_at DESC, id DESC LIMIT 20`):** Performs a direct B-Tree index seek in constant time $\mathcal{O}(1)$ regardless of table depth. It is also immune to page drift caused by concurrent row inserts.

### Q68: What are the 4 core components of the Relay Connection Specification?
**Answer:**
1. `Connection`: Top-level pagination wrapper containing `edges`, `pageInfo`, and optional `totalCount`.
2. `Edge`: Wrapper containing the `node` (actual entity) and an opaque `cursor` string.
3. `Node`: The target domain entity.
4. `PageInfo`: Metadata containing `hasNextPage`, `hasPreviousPage`, `startCursor`, and `endCursor`.

### Q69: Why should GraphQL cursors always be opaque Base64 strings?
**Answer:** Cursors should be an implementation detail hidden from the client. Encoding composite sort keys (e.g., `timestamp:id`) into Base64 prevents clients from attempting manual arithmetic manipulation and allows backend engineers to modify sorting strategies without breaking frontend clients.

### Q70: How do you determine `hasNextPage` without executing an expensive `SELECT COUNT(*)`?
**Answer:** Query the database for `limit + 1` records. If the database returns `limit + 1` records, you know that a next page exists (`hasNextPage = true`); discard the extra record and return `limit` records. If it returns $\le limit$, you have reached the end (`hasNextPage = false`).

### Q71: How do you implement dynamic multi-attribute filtering in Spring Data JPA for GraphQL?
**Answer:** Define a GraphQL `input FilterInput` matching filterable fields. In Java, construct a dynamic Spring Data `Specification<T>` using the JPA Criteria API (`CriteriaBuilder.equal()`, `greaterThanOrEqualTo()`, `like()`), combining predicates dynamically based on non-null input fields.

### Q72: What database indexes are required to optimize Keyset Pagination?
**Answer:** A composite B-Tree index covering the sort column(s) and the unique primary key in the exact sort direction:
```sql
CREATE INDEX idx_orders_created_at_id ON orders (created_at DESC, id DESC);
```

### Q73: What is the PostgreSQL parameter limit in `IN` clauses and how do you handle it in batching?
**Answer:** PostgreSQL supports up to 65,535 parameters, but database performance degrades if `IN` clauses contain thousands of parameters. In high-volume systems, partition batch IDs into chunks of 500 or 1,000 using Guava `Lists.partition()` or Java Streams.

### Q74: How does Hibernate Optimistic Locking (`@Version`) work during GraphQL mutations?
**Answer:** The entity includes a `@Version Long version` column. When updating, Hibernate executes:
```sql
UPDATE table SET col = ?, version = version + 1 WHERE id = ? AND version = ?;
```
If another transaction updated the row concurrently, the version predicate matches 0 rows, and Hibernate throws an `ObjectOptimisticLockingFailureException`, preventing dirty writes.

### Q75: How do you inspect the SQL generated by Hibernate during GraphQL queries?
**Answer:** Set `spring.jpa.show-sql=true` and configure the logger:
```yaml
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```
This logs SQL statements along with bound prepared statement parameters.

---

## PART 6: Security, JWT, Authorization & Hardening (Questions 76–85)

### Q76: Explain the Two-Tier Security Model for GraphQL.
**Answer:**
- **Tier 1 (HTTP Transport Layer):** Spring Security filters intercept the request at the servlet level, validate the Bearer JWT token signature and expiration, and establish the `SecurityContext`.
- **Tier 2 (Resolver & Field Layer):** Authorization checks are enforced within the GraphQL engine using method-level security (`@PreAuthorize`) and field-level redaction resolvers to control access to specific graph nodes.

### Q77: Why does asynchronous resolver execution break Spring Security's `ThreadLocal`?
**Answer:** `SecurityContextHolder` stores authentication in a `ThreadLocal` variable bound to the servlet thread. When `graphql-java` offloads field resolution to worker threads (`CompletableFuture`), the new thread has an empty `ThreadLocal`. This is resolved in Spring Boot 3.x using **Micrometer Context Propagation** or `SecurityContextHolder.MODE_INHERITABLETHREADLOCAL`.

### Q78: How do you implement Field-Level Authorization in GraphQL?
**Answer:** Implement a dedicated `@SchemaMapping` resolver for the sensitive field. Inside the method, inspect the user's `Authentication` authorities. If the user lacks the required role, return `null` (or a masked value) and optionally append a non-fatal field error to the execution context. The field must be declared as **nullable** in the SDL so that partial data is preserved.

### Q79: What is Broken Object Level Authorization (BOLA / IDOR) in GraphQL and how do you prevent it?
**Answer:** BOLA occurs when an attacker manipulates an ID argument (e.g., `order(id: "999")`) to view another user's private data. 
**Prevention:** Never trust client-supplied IDs. Extract the authenticated `userId` directly from the validated JWT token and push ownership filters into the database query:
`WHERE id = :id AND customer_id = :authenticatedUserId`.

### Q80: What is a Query Depth Attack and how is it mitigated?
**Answer:** An attacker submits an intensely nested recursive query (e.g., 20 levels of `user -> friends -> user -> friends`), forcing the server to construct millions of AST objects and exhaust database connections. It is mitigated by registering a `MaxQueryDepthInstrumentation` bean that analyzes the AST and rejects queries exceeding a threshold (e.g., depth $> 7$).

### Q81: What is a Query Complexity Attack and how does it calculate query costs?
**Answer:** An attacker requests expensive fields or massive pagination limits across broad relations. A `MaxQueryComplexityInstrumentation` calculates cost by assigning weights: scalars cost 1, lists multiply child costs by their limit argument ($\text{limit} \times \sum \text{child costs}$). If total complexity exceeds a budget (e.g., 250 points), the query is blocked before execution.

### Q82: Why must Schema Introspection be disabled in production?
**Answer:** Introspection queries (`__schema`, `__type`) expose every type, field, argument, deprecation, and description in your API. Attackers use introspection to map system topology and locate unhardened administrative endpoints. Disable it via `spring.graphql.schema.introspection.enabled=false`.

### Q83: What is a Batching Attack in GraphQL?
**Answer:** An attacker sends an HTTP POST request containing a JSON array of 5,000 queries. If the server executes all 5,000 in parallel, it can overwhelm the database and cause a denial of service. Mitigate by configuring web server request size limits and disabling multi-operation execution if not explicitly required.

### Q84: How do you implement distributed rate limiting for GraphQL?
**Answer:** Implement the Token Bucket Algorithm using Redis. Intercept incoming requests via a servlet filter, hash the client's IP or JWT `sub` claim into a Redis key (`rate:graphql:<id>`), and increment a counter with a TTL. If requests exceed the limit (e.g., 100/min), return an `HTTP 429 Too Many Requests`.

### Q85: What is Query Safelisting (Persisted Operations)?
**Answer:** An enterprise security practice where arbitrary client queries are completely forbidden in production. Frontend applications extract all production queries during CI/CD, generate cryptographic hashes (SHA-256), and register them in a server-side whitelist. The server only executes queries matching a pre-registered hash, completely eliminating injection and DoS attacks.

---

## PART 7: Real-Time Subscriptions, WebSockets & Reactive Systems (Questions 86–92)

### Q86: What protocol is used for GraphQL Subscriptions over WebSockets?
**Answer:** The modern standard is **`graphql-transport-ws`** (replacing the legacy, deprecated `subscriptions-transport-ws`). It defines a formal message exchange (`connection_init`, `connection_ack`, `subscribe`, `next`, `complete`, `error`, `ping`, `pong`).

### Q87: How do you authenticate a GraphQL WebSocket connection?
**Answer:** WebSockets cannot send standard HTTP headers after the handshake. Authentication occurs during the initial protocol handshake: the client sends a `connection_init` message containing an `authToken` in its payload. A Spring `WebSocketGraphQlInterceptor` intercepts this message, decodes the JWT, and binds the `Authentication` object to the WebSocket session attributes.

### Q88: How does Spring for GraphQL represent subscriptions in Java code?
**Answer:** Controller methods are annotated with `@SubscriptionMapping` and must return a reactive stream **`Publisher<T>`**, typically a Project Reactor **`reactor.core.publisher.Flux<T>`**. Spring subscribes to the `Flux` and forwards emitted items to the client over WebSocket.

### Q89: What is `Sinks.Many<T>` in Project Reactor and how is it used in subscriptions?
**Answer:** `Sinks.Many<T>` is a thread-safe, reactive standalone publisher that allows programmatically pushing events into a multi-subscriber stream. When a domain event occurs (e.g., chat message sent), your service calls `sink.emitNext(event)`, which pushes the item to all active GraphQL WebSocket subscribers.

### Q90: How do you handle reactive backpressure in GraphQL subscriptions?
**Answer:** When a slow client cannot consume events as fast as the server produces them, Project Reactor applies backpressure strategies:
- `.onBackpressureBuffer(int capacity)`: Buffers unconsumed events up to a limit.
- `.onBackpressureDrop()`: Drops incoming events if the downstream subscriber cannot keep up, preventing JVM memory exhaustion.

### Q91: How do you scale GraphQL Subscriptions across a multi-node cluster?
**Answer:** Because WebSocket connections are stateful and pinned to a specific server instance, a multi-node cluster requires an **event bus backplane (Apache Kafka or Redis Pub/Sub)**. When an event occurs on Node 1, it publishes to Kafka. All nodes consume from Kafka and broadcast the event to their locally connected WebSocket clients.

### Q92: What is the difference between WebSocket Subscriptions and Server-Sent Events (SSE)?
**Answer:**
- **WebSocket:** Bi-directional, full-duplex TCP connection. Requires handshake upgrade. Can be blocked by aggressive corporate proxies.
- **Server-Sent Events (SSE):** Unidirectional server-to-client stream over standard HTTP/1.1 or HTTP/2 (`text/event-stream`). Works seamlessly through standard HTTP proxies and firewalls without protocol switching.

---

## PART 8: Microservices, Apollo Federation & Gateways (Questions 93–97)

### Q93: What is Apollo Federation v2?
**Answer:** Apollo Federation v2 is an enterprise architecture that composes multiple independently managed GraphQL services (called **Subgraphs**) into a single, unified GraphQL API (the **Supergraph**). A high-performance router (Apollo Router) sits at the network edge, breaks incoming client queries into execution plans, and routes requests to downstream subgraphs.

### Q94: What is the `@key` directive in Apollo Federation?
**Answer:** The `@key(fields: "id")` directive defines an entity's primary key. It indicates that the entity can be uniquely identified and referenced or extended across different subgraph services.

### Q95: What is the difference between `@shareable` and `@external` in Federation v2?
**Answer:**
- `@shareable`: Declares that a field can be resolved by more than one subgraph (e.g., a common `Product.title` field).
- `@external`: Declares that a field is owned by another subgraph, but its value is required locally by this subgraph to compute a local field (using `@requires`).

### Q96: What is an `_entities` query in an Apollo Federation subgraph?
**Answer:** It is a special query automatically generated by the federation engine:
```graphql
query (_representations: [_Any!]!) { _entities(representations: $_representations) { ... } }
```
The Gateway Router uses this query to execute batched entity lookups across subgraphs during distributed query plan execution.

### Q97: How do you handle distributed transactions across GraphQL microservices?
**Answer:** Distributed 2-Phase Commit (2PC) is an anti-pattern in high-scale microservices. Instead, use the **Saga Pattern** with asynchronous compensating transactions:
1. The Gateway issues a mutation to the Order Service (Order created in `PENDING` status).
2. The Order Service emits an event to Apache Kafka.
3. The Payment Service charges the card. If it fails, an event triggers a compensating action in Order Service to mark the order as `CANCELLED`.

---

## PART 9: Production Troubleshooting, System Design & Coding (Questions 98–100)

### Q98: System Design: Design a High-Throughput E-Commerce Supergraph
**Requirements:** 50,000 requests/sec, mobile and web frontends, subgraphs for User, Catalog, Inventory, and Checkout.
**Architecture Blueprint:**
1. **Edge Tier:** Cloudflare CDN terminating TLS and caching Automated Persisted Queries (APQ) via HTTP GET.
2. **Gateway Tier:** Apollo Router deployed across AWS ECS/EKS clusters, auto-scaling based on CPU/RAM.
3. **Subgraph Services:** Spring Boot 3.x microservices running on Java 21 LTS with Virtual Threads (Project Loom) for non-blocking I/O.
4. **Data Tier:** PostgreSQL read-replicas for catalog queries; Redis cluster for resolver caching and rate limiting; Apache Kafka for asynchronous order event processing.
5. **Observability:** Prometheus scraping `graphql.timer` and `graphql.datafetcher.timer` metrics; Grafana dashboards tracking p99 latencies; OpenTelemetry distributed tracing across all subgraphs.

### Q99: Production Incident Walkthrough: High CPU & PostgreSQL Connection Starvation
**Scenario:** Following a marketing push, the Spring Boot GraphQL backend CPU spikes to 100%, and PostgreSQL logs show `FATAL: remaining connection slots are reserved for non-superuser connections`. API p99 latency jumps from 30ms to 8,000ms.
**Root Cause Diagnostics & Resolution:**
1. **Diagnosis:** A newly deployed frontend feature queried `orders { lineItems { product { reviews } } }`. The backend lacked `@BatchMapping` resolvers for `lineItems` and `product`, causing an un-batched N+1 loop executing 50,000 individual SQL queries per second.
2. **Immediate Remediation:** In the API Gateway / Load Balancer, temporarily set a maximum query complexity limit of 150 to reject the un-batched deep query.
3. **Permanent Fix:** 
   - Implement `@BatchMapping` on `Order.lineItems` and `LineItem.product` to batch database lookups.
   - Configure HikariCP connection pool settings with proper connection timeouts (`connection-timeout: 30000`, `maximum-pool-size: 25`).
   - Add integration tests with Testcontainers asserting that fetching 100 orders generates exactly 3 SQL queries.

### Q100: Coding Exercise: Implement a Spring Boot `@BatchMapping` Resolver from Scratch
**Task:** Given a list of `OrderDto` records, implement a production-grade, thread-safe `@BatchMapping` resolver that loads the corresponding `CustomerDto` records without N+1 queries.
```java
package com.upskill.graphql.controller;

import com.upskill.graphql.dto.CatalogDtos.CustomerDto;
import com.upskill.graphql.dto.CatalogDtos.OrderDto;
import com.upskill.graphql.entity.CustomerEntity;
import com.upskill.graphql.repository.CustomerRepository;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Controller
public class OptimizedOrderCustomerBatchController {

    private final CustomerRepository customerRepository;

    public OptimizedOrderCustomerBatchController(CustomerRepository customerRepository) {
        this.customerRepository = Objects.requireNonNull(customerRepository);
    }

    /**
     * Resolves Order.customer: Customer! in a single batched SQL query
     */
    @BatchMapping(typeName = "Order", field = "customer")
    public Map<OrderDto, CustomerDto> customer(List<OrderDto> orders) {
        // 1. Extract deduplicated customer IDs
        List<Long> customerIds = orders.stream()
                .map(OrderDto::customerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 2. Fetch all customers in ONE SQL query: WHERE id IN (?, ?, ...)
        Map<Long, CustomerDto> customerMap = customerRepository.findAllById(customerIds)
                .stream()
                .collect(Collectors.toMap(
                        CustomerEntity::getId,
                        this::toDto
                ));

        // 3. Map each parent OrderDto back to its resolved CustomerDto
        return orders.stream()
                .collect(Collectors.toMap(
                        order -> order,
                        order -> customerMap.get(order.customerId())
                ));
    }

    private CustomerDto toDto(CustomerEntity entity) {
        return new CustomerDto(entity.getId(), entity.getFullName(), entity.getEmail(), entity.getTier());
    }
}
```
