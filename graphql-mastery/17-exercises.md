# Module 17: Comprehensive Practical Exercises & Engineering Assignments

Welcome to Module 17 of the **GraphQL Mastery Guide**. This module contains a rigorous set of **Hands-On Exercises, Debugging Scenarios, and Architectural Assignments** designed to cement your production expertise in **Java 17+, Spring Boot 3.x, Spring Data JPA, and PostgreSQL**.

> [!TIP]
> **Exercise Guidelines:** Attempt to solve all exercises, write code, and fix bugs in your local IDE before consulting the complete reference solutions in [`18-exercise-solutions.md`](./18-exercise-solutions.md).

---

## SECTION 1: Conceptual Mastery Questions

### Question 1.1: AST Parsing & Execution
Trace the step-by-step path of a GraphQL request string from the moment it hits `WebMvcGraphQlHandler` to the moment the final JSON byte array is sent back over HTTP. At what exact step are query depth limits evaluated?

### Question 1.2: Null Propagation Behavior
Given the schema below:
```graphql
type Query {
    customer(id: ID!): Customer!
}
type Customer {
    id: ID!
    profile: Profile!
}
type Profile {
    bio: String!
    avatarUrl: String
}
```
If a database record exists for `Customer` and `Profile`, but the database column for `Profile.bio` is `NULL`, what is the exact JSON response returned by the server? What happens if `Query.customer` is changed to nullable `customer(id: ID!): Customer`?

### Question 1.3: Concurrency Semantics in Operations
Why does the GraphQL specification strictly mandate sequential execution for root fields in a `Mutation` while allowing concurrent parallel execution for root fields in a `Query`? Give a concrete business scenario where parallel mutation execution would corrupt financial data.

### Question 1.4: REST vs. GraphQL Caching Dilemma
Why does an HTTP reverse proxy cache (such as Varnish or Cloudflare CDN) fail to cache standard GraphQL queries out of the box? What two architectural techniques enable CDN caching for GraphQL APIs?

### Question 1.5: Security at the Transport vs. Field Layer
Explain why configuring `http.authorizeHttpRequests(auth -> auth.requestMatchers("/graphql").hasRole("USER"))` is completely insufficient to protect administrative queries and sensitive fields in an enterprise GraphQL application.

---

## SECTION 2: Hands-On Coding Exercises

### Coding Exercise 2.1: Custom `UUID` Scalar Implementation
**Requirement:** 
Implement a custom scalar `UUID` from scratch in Java 17+ using `graphql.schema.GraphQLScalarType` and `Coercing<UUID, String>`.
- `serialize`: Converts a `java.util.UUID` into an RFC-4122 string.
- `parseValue`: Parses a valid UUID string variable into a `java.util.UUID` or throws `CoercingParseValueException`.
- `parseLiteral`: Parses a string AST literal or throws `CoercingParseLiteralException`.
- Register the scalar via Spring Boot `RuntimeWiringConfigurer`.

### Coding Exercise 2.2: Relay-Compliant Keyset Pagination Resolver
**Requirement:**
Given an existing Spring Data JPA `ProductRepository`, implement a query resolver for:
```graphql
products(first: Int, after: String, minPrice: BigDecimal): ProductConnection!
```
- Support Keyset pagination using `(created_at DESC, id DESC)`.
- Encode cursors as Base64 strings.
- Retrieve `limit + 1` rows to calculate `PageInfo.hasNextPage` without executing a `COUNT(*)` query.
- Construct the `ProductConnection`, `ProductEdge`, and `PageInfo` objects.

### Coding Exercise 2.3: Production `@BatchMapping` with Many-to-One
**Requirement:**
Implement an optimized Spring for GraphQL controller for:
```graphql
type Order {
    id: ID!
    customer: Customer!
}
```
Write a `@BatchMapping(typeName = "Order", field = "customer")` method that receives `List<OrderDto> orders` and returns a `Map<OrderDto, CustomerDto>` by executing a single batched SQL query using `customerRepository.findAllById(customerIds)`.

### Coding Exercise 2.4: One-to-Many Grouped Batching
**Requirement:**
For the schema:
```graphql
type Customer {
    id: ID!
    orders: [Order!]!
}
```
Write a `@BatchMapping(typeName = "Customer", field = "orders")` method that receives `List<CustomerDto> customers` and groups orders by their parent customer using Java Streams, returning `Map<CustomerDto, List<OrderDto>>`.

### Coding Exercise 2.5: Custom Global Exception Handler
**Requirement:**
Create a Spring Boot `@ControllerAdvice` global error handler using `@GraphQlExceptionHandler`:
- Map `EntityNotFoundException` to `ErrorType.NOT_FOUND` with custom extension `"errorCode": "RESOURCE_NOT_FOUND"`.
- Map `AccessDeniedException` to `ErrorType.FORBIDDEN`.
- Map all uncaught exceptions to `ErrorType.INTERNAL_ERROR`, generating a unique `UUID correlationId`, logging the stack trace internally, and redacting the internal error message from the client.

---

## SECTION 3: Real-World Debugging Exercises

### Debugging Exercise 3.1: The N+1 Query Disaster
**Scenario:** 
A developer submits the following controller for review. In load testing with 50 concurrent users, PostgreSQL CPU hits 100% and connection pool timeouts spike.
```java
@Controller
public class BrokenCatalogController {

    private final ProductRepository productRepo;
    private final ReviewRepository reviewRepo;

    public BrokenCatalogController(ProductRepository p, ReviewRepository r) {
        this.productRepo = p;
        this.reviewRepo = r;
    }

    @QueryMapping
    public List<ProductDto> featuredProducts() {
        return productRepo.findFeatured();
    }

    @SchemaMapping(typeName = "Product", field = "reviews")
    public List<ReviewDto> reviews(ProductDto product) {
        return reviewRepo.findByProductId(product.id());
    }
}
```
**Task:** Identify the exact architectural bug, explain what SQL is generated when fetching 100 featured products, and write the corrected `@BatchMapping` implementation.

---

### Debugging Exercise 3.2: The Asynchronous `SecurityContext` Disappearance
**Scenario:**
A developer added `@PreAuthorize("hasRole('ADMIN')")` to a child field resolver. When tested via an authenticated client with an admin JWT token, the root query succeeds, but the child field always throws an `AccessDeniedException: An Authentication object was not found in the SecurityContext`.
```java
@Controller
public class AdminFieldController {

    @SchemaMapping(typeName = "Customer", field = "internalAuditNotes")
    @PreAuthorize("hasRole('ADMIN')")
    public String internalAuditNotes(CustomerDto customer) {
        return customer.auditNotes();
    }
}
```
**Task:** Explain why the `SecurityContext` is lost during field resolution, and provide the exact Spring Boot configuration fix required to propagate security context across asynchronous GraphQL execution threads.

---

### Debugging Exercise 3.3: Positional Deserialization Disaster in `@BatchMapping`
**Scenario:**
A developer implemented batch loading returning a `List<CustomerDto>`:
```java
@BatchMapping(typeName = "Order", field = "customer")
public List<CustomerDto> customer(List<OrderDto> orders) {
    List<Long> customerIds = orders.stream().map(OrderDto::customerId).toList();
    return customerRepository.findAllById(customerIds);
}
```
In production, customers report seeing other users' names and email addresses attached to their orders!
**Task:** Explain why this code causes severe cross-tenant data corruption, and rewrite the method to guarantee deterministic mapping.

---

## SECTION 4: Real-World Enterprise Scenario Challenges

### Scenario 4.1: Financial Mutation Idempotency
**Scenario:** 
In an e-commerce checkout mutation `checkout(input: CheckoutInput!): CheckoutPayload!`, mobile users on spotty subway Wi-Fi frequently tap "Place Order" twice, or their connection drops right after the transaction commits in PostgreSQL. 
**Design Challenge:** Design a distributed idempotency mechanism using Redis and Spring for GraphQL that prevents duplicate charges while ensuring the client receives the exact same order confirmation response.

### Scenario 4.2: Field-Level HIPAA Redaction
**Scenario:**
In a hospital telemedicine system, a single query `patient(id: ID!)` is accessed by Doctors, Nurses, and Billing Clerks. 
- Doctors can see all fields.
- Nurses can see medical vitals but cannot see billing credit card info.
- Billing Clerks can see costs and insurance but cannot see clinical diagnosis text or notes.
**Design Challenge:** Design the SDL schema and Spring for GraphQL resolvers that enforce field-level redaction without failing the query, returning `null` for unauthorized fields while delivering authorized fields.

### Scenario 4.3: Mitigating a Recursive DoS Attack
**Scenario:**
An attacker discovers an unhardened GraphQL endpoint and repeatedly sends:
```graphql
query {
    user(id: 1) {
        organizations {
            members {
                organizations {
                    members { id }
                }
            }
        }
    }
}
```
**Design Challenge:** Provide the complete Java configuration and tuning parameters to block this attack at the AST validation stage without modifying controller code.

---

## SECTION 5: Capstone Mini-Project Assignment

### Mini-Project: Resilient Digital Wallet GraphQL Microservice
**Objective:** Build a runnable Spring Boot 3.x GraphQL application for a peer-to-peer digital wallet with the following requirements:
1. **Domain Model:**
   - `Wallet`: `id`, `accountNumber`, `balance: BigDecimal`, `currencyCode`, `ownerId`.
   - `Transaction`: `id`, `amount: BigDecimal`, `type: (CREDIT | DEBIT)`, `referenceNote`, `createdAt: DateTime`.
2. **Operations:**
   - Query: `wallet(id: ID!): Wallet` with keyset-paginated `transactions(first: Int, after: String)`.
   - Mutation: `transferFunds(input: TransferFundsInput!): TransferFundsPayload!` with ACID transaction isolation and optimistic locking.
3. **Engineering Guardrails:**
   - Zero N+1 queries.
   - Max query depth $\le 6$.
   - JWT authentication with ownership validation (users can only query their own wallet).
   - Testcontainers integration test verifying atomic fund transfers.

*(Proceed to [`18-exercise-solutions.md`](./18-exercise-solutions.md) for full reference implementations!)*
