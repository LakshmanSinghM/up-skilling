# Module 18: Complete Practical Exercise Solutions & Reference Implementations

Welcome to Module 18 of the **GraphQL Mastery Guide**. This module provides production-grade, compilable **Reference Solutions** for all conceptual questions, coding exercises, debugging tasks, scenarios, and the capstone mini-project from [`17-exercises.md`](./17-exercises.md).

---

## SOLUTIONS FOR SECTION 1: Conceptual Mastery

### Solution 1.1: Request Lifecycle & Depth Evaluation Point
1. **HTTP Ingestion:** `WebMvcGraphQlHandler` receives HTTP POST at `/graphql`, extracting `query`, `variables`, and `operationName`.
2. **Context Creation:** Interceptors (`WebGraphQlInterceptor`) extract HTTP headers and populate the `GraphQLContext`.
3. **Lexical Analysis & Parsing:** The Antlr4 parser transforms the raw query string into a `Document` AST.
4. **Validation & Instrumentation (Depth Check Point):**
   - The engine triggers registered `Instrumentation` beans.
   - **`MaxQueryDepthInstrumentation` evaluates the AST tree depth at this exact moment, BEFORE any execution plan or data fetcher runs.** If depth exceeds the limit, an `AbortExecutionException` is thrown, returning an error immediately.
5. **Execution Planning:** If validation passes, root operations are mapped to root `DataFetcher` instances.
6. **Field Resolution:** DataFetchers execute (in parallel for Queries, sequentially for Mutations), invoking JPA repositories or services.
7. **Response Formatting:** Results and any field errors are serialized into `{ "data": ..., "errors": ... }` JSON and flushed over the HTTP response.

### Solution 1.2: Null Propagation Behavior
- **Scenario A (`Query.customer` is Non-Null `Customer!`):**
  Because `Profile.bio` is marked `String!`, it cannot be null. When the database returns null, the engine bubbles null to `Customer.profile`. But `profile` is ALSO marked `Profile!` (Non-Null), so null bubbles to `customer`. But `Query.customer` is ALSO marked `Customer!` (Non-Null). 
  **Result:** The entire data envelope collapses to null:
  ```json
  {
    "data": null,
    "errors": [{ "message": "Cannot return null for non-nullable field Profile.bio", "path": ["customer", "profile", "bio"] }]
  }
  ```
- **Scenario B (`Query.customer` is Nullable `Customer`):**
  The null bubbles from `bio` $\rightarrow$ `profile` $\rightarrow$ `customer`. Because `customer` is nullable, bubbling halts there.
  **Result:**
  ```json
  {
    "data": { "customer": null },
    "errors": [{ "message": "Cannot return null for non-nullable field Profile.bio", "path": ["customer", "profile", "bio"] }]
  }
  ```

### Solution 1.3: Concurrency in Mutations vs. Queries
- **Why Mutations are Serial:** Mutations produce side effects. Consider a client sending:
  1. `debitAccount(amount: $500)`
  2. `closeAccount()`
  If these ran in parallel, `closeAccount()` could complete before `debitAccount()`, causing the debit to fail or overdraft an already closed account. Serial execution guarantees deterministic transaction ordering.

### Solution 1.4: Caching Dilemma & Two Solutions
Standard HTTP proxies rely on HTTP `GET` requests and distinct URL paths (`/api/v1/products/101`). GraphQL uses `POST /graphql` with dynamic request bodies.
**Solutions:**
1. **Automated Persisted Queries (APQ):** Hash the query to a SHA-256 string and issue an HTTP `GET /graphql?hash=xyz&variables={...}`. Proxies and CDNs can cache this GET URL.
2. **Client Normalized Cache:** Apollo Client / Relay maintain an in-memory normalized cache indexed by `__typename:id`, updating views without network calls.

### Solution 1.5: Security at Transport vs. Field Layer
A transport rule `requestMatchers("/graphql").hasRole("USER")` only checks if the caller has logged in. Once admitted, the caller can execute ANY query inside the GraphQL schema—including administrative queries (`purgeDatabase`) or sensitive fields (`Customer.taxId`). Fine-grained authorization must be applied inside the schema using `@PreAuthorize` and field-level resolvers.

---

## SOLUTIONS FOR SECTION 2: Hands-On Coding Exercises

### Solution 2.1: Custom `UUID` Scalar
```java
package com.upskill.graphql.scalars;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.util.Locale;
import java.util.UUID;

public final class UuidScalar {

    public static final GraphQLScalarType INSTANCE = GraphQLScalarType.newScalar()
            .name("UUID")
            .description("RFC-4122 standard universally unique identifier")
            .coercing(new Coercing<UUID, String>() {
                @Override
                public String serialize(Object dataFetcherResult, GraphQLContext context, Locale locale)
                        throws CoercingSerializeException {
                    if (dataFetcherResult instanceof UUID uuid) {
                        return uuid.toString();
                    }
                    if (dataFetcherResult instanceof String str) {
                        try {
                            return UUID.fromString(str).toString();
                        } catch (IllegalArgumentException e) {
                            throw new CoercingSerializeException("Invalid UUID string: " + str);
                        }
                    }
                    throw new CoercingSerializeException("Expected UUID or valid String, got: " + dataFetcherResult);
                }

                @Override
                public UUID parseValue(Object input, GraphQLContext context, Locale locale)
                        throws CoercingParseValueException {
                    if (input instanceof String str) {
                        try {
                            return UUID.fromString(str);
                        } catch (IllegalArgumentException e) {
                            throw new CoercingParseValueException("String cannot be parsed into UUID: " + str);
                        }
                    }
                    throw new CoercingParseValueException("Expected String for UUID scalar, got: " + input);
                }

                @Override
                public UUID parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale)
                        throws CoercingParseLiteralException {
                    if (input instanceof StringValue stringValue) {
                        try {
                            return UUID.fromString(stringValue.getValue());
                        } catch (IllegalArgumentException e) {
                            throw new CoercingParseLiteralException("Invalid UUID literal: " + stringValue.getValue());
                        }
                    }
                    throw new CoercingParseLiteralException("Expected StringValue literal for UUID, got: " + input);
                }
            })
            .build();

    @Configuration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        public RuntimeWiringConfigurer uuidWiringConfigurer() {
            return wiring -> wiring.scalar(INSTANCE);
        }
    }
}
```

---

### Solution 2.2: Relay-Compliant Keyset Pagination Resolver
```java
package com.upskill.graphql.pagination;

import com.upskill.graphql.dto.ProductDto;
import com.upskill.graphql.entity.ProductEntity;
import com.upskill.graphql.repository.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Controller
public class ProductPaginationController {

    private final ProductRepository productRepository;

    public ProductPaginationController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @QueryMapping
    public ProductConnection products(
            @Argument Integer first,
            @Argument String after,
            @Argument BigDecimal minPrice) {

        int limit = (first != null && first > 0) ? Math.min(first, 100) : 20;
        DecodedCursor cursor = decodeCursor(after);

        // Build dynamic Keyset specification: WHERE (price >= minPrice) AND ((created_at < cursorTime) OR (created_at = cursorTime AND id < cursorId))
        Specification<ProductEntity> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (minPrice != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("unitPrice"), minPrice));
            }
            if (cursor != null) {
                var timeBefore = cb.lessThan(root.get("createdAt"), cursor.createdAt());
                var timeEq = cb.equal(root.get("createdAt"), cursor.createdAt());
                var idBefore = cb.lessThan(root.get("id"), cursor.id());
                predicates.add(cb.or(timeBefore, cb.and(timeEq, idBefore)));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        // Query limit + 1
        PageRequest pageRequest = PageRequest.of(0, limit + 1, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        List<ProductEntity> entities = productRepository.findAll(spec, pageRequest).getContent();

        boolean hasNextPage = entities.size() > limit;
        List<ProductEntity> pageData = hasNextPage ? entities.subList(0, limit) : entities;

        List<ProductEdge> edges = pageData.stream()
                .map(e -> new ProductEdge(toDto(e), encodeCursor(e.getCreatedAt(), e.getId())))
                .toList();

        String startCursor = edges.isEmpty() ? null : edges.get(0).cursor();
        String endCursor = edges.isEmpty() ? null : edges.get(edges.size() - 1).cursor();
        PageInfo pageInfo = new PageInfo(hasNextPage, after != null, startCursor, endCursor);

        return new ProductConnection(edges, pageInfo);
    }

    private String encodeCursor(Instant instant, Long id) {
        String raw = instant.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private DecodedCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String[] parts = raw.split(":");
        return new DecodedCursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
    }

    private ProductDto toDto(ProductEntity e) {
        return new ProductDto(e.getId(), e.getSku(), e.getTitle(), e.getUnitPrice(), e.getCreatedAt());
    }

    record DecodedCursor(Instant createdAt, Long id) {}
    public record ProductConnection(List<ProductEdge> edges, PageInfo pageInfo) {}
    public record ProductEdge(ProductDto node, String cursor) {}
    public record PageInfo(boolean hasNextPage, boolean hasPreviousPage, String startCursor, String endCursor) {}
}
```

---

### Solution 2.3: Production `@BatchMapping` (Order $\rightarrow$ Customer)
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
public class OrderBatchMappingController {

    private final CustomerRepository customerRepository;

    public OrderBatchMappingController(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @BatchMapping(typeName = "Order", field = "customer")
    public Map<OrderDto, CustomerDto> customer(List<OrderDto> orders) {
        List<Long> customerIds = orders.stream()
                .map(OrderDto::customerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, CustomerDto> customerById = customerRepository.findAllById(customerIds)
                .stream()
                .collect(Collectors.toMap(
                        CustomerEntity::getId,
                        e -> new CustomerDto(e.getId(), e.getFullName(), e.getEmail(), e.getTier())
                ));

        return orders.stream().collect(Collectors.toMap(
                order -> order,
                order -> customerById.get(order.customerId())
        ));
    }
}
```

---

### Solution 2.4: One-to-Many Grouped Batching (Customer $\rightarrow$ Orders)
```java
package com.upskill.graphql.controller;

import com.upskill.graphql.dto.CatalogDtos.CustomerDto;
import com.upskill.graphql.dto.CatalogDtos.OrderDto;
import com.upskill.graphql.entity.OrderEntity;
import com.upskill.graphql.repository.OrderRepository;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class CustomerOrdersBatchController {

    private final OrderRepository orderRepository;

    public CustomerOrdersBatchController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @BatchMapping(typeName = "Customer", field = "orders")
    public Map<CustomerDto, List<OrderDto>> orders(List<CustomerDto> customers) {
        List<Long> customerIds = customers.stream().map(CustomerDto::id).toList();

        // Single SQL: SELECT * FROM orders WHERE customer_id IN (?, ?, ...)
        List<OrderEntity> orderEntities = orderRepository.findByCustomerIdIn(customerIds);

        Map<Long, List<OrderDto>> ordersByCustomerId = orderEntities.stream()
                .collect(Collectors.groupingBy(
                        OrderEntity::getCustomerId,
                        Collectors.mapping(
                                e -> new OrderDto(e.getId(), e.getOrderNumber(), e.getTotalAmount(), e.getCustomerId(), e.getStatus(), e.getCreatedAt()),
                                Collectors.toList()
                        )
                ));

        return customers.stream().collect(Collectors.toMap(
                customer -> customer,
                customer -> ordersByCustomerId.getOrDefault(customer.id(), new ArrayList<>())
        ));
    }
}
```

---

### Solution 2.5: Global Exception Handler
```java
package com.upskill.graphql.exception;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@ControllerAdvice
public class ProductionGraphQlExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductionGraphQlExceptionHandler.class);

    @GraphQlExceptionHandler
    public GraphQLError handleNotFound(EntityNotFoundException ex, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.NOT_FOUND)
                .message(ex.getMessage())
                .extensions(Map.of("errorCode", "RESOURCE_NOT_FOUND", "timestamp", Instant.now().toString()))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleAccessDenied(AccessDeniedException ex, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.FORBIDDEN)
                .message("Access denied: You lack necessary permissions")
                .extensions(Map.of("errorCode", "FORBIDDEN_ACCESS", "timestamp", Instant.now().toString()))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleAllUncaught(Throwable ex, DataFetchingEnvironment env) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled Exception [Correlation: {}]: ", correlationId, ex);

        return GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.INTERNAL_ERROR)
                .message("An unexpected internal error occurred. Please contact support with this correlation ID.")
                .extensions(Map.of("correlationId", correlationId, "timestamp", Instant.now().toString()))
                .build();
    }
}
```

---

## SOLUTIONS FOR SECTION 3: Debugging Exercises

### Solution 3.1: Fix for N+1 Catalog Disaster
**Root Cause:** Naive `@SchemaMapping reviews(ProductDto product)` executes 1 query per product. For 100 products, it executes 101 queries.
**Fix:** Replace with `@BatchMapping`:
```java
@BatchMapping(typeName = "Product", field = "reviews")
public Map<ProductDto, List<ReviewDto>> reviews(List<ProductDto> products) {
    List<Long> productIds = products.stream().map(ProductDto::id).toList();
    List<ReviewEntity> reviews = reviewRepo.findByProductIdIn(productIds);
    Map<Long, List<ReviewDto>> grouped = reviews.stream()
            .collect(Collectors.groupingBy(r -> r.getProduct().getId(), Collectors.mapping(this::toDto, Collectors.toList())));
    return products.stream().collect(Collectors.toMap(p -> p, p -> grouped.getOrDefault(p.id(), List.of())));
}
```

### Solution 3.2: Fix for Asynchronous `SecurityContext` Loss
**Root Cause:** When `graphql-java` resolves child fields asynchronously, it switches to a background worker thread where `SecurityContextHolder` is empty.
**Fix:** Add `SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL)` or register Micrometer context propagation:
```java
@Configuration(proxyBeanMethods = false)
public class SecurityContextPropagationConfig {
    @PostConstruct
    public void configureStrategy() {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }
}
```

### Solution 3.3: Fix for Positional List Deserialization in `@BatchMapping`
**Root Cause:** Returning `List<CustomerDto>` requires strict positional matching. If any customer record is deleted from PostgreSQL, the returned list has fewer items or different ordering, shifting indices and causing Order A to receive Customer B's data!
**Fix:** Always return `Map<OrderDto, CustomerDto>` to ensure explicit key-value pairing.

---

## SOLUTIONS FOR SECTION 4: Real-World Scenarios

### Solution 4.1: Financial Mutation Idempotency with Redis
1. The client generates a unique UUID `idempotencyKey` and passes it in `CreateOrderInput`.
2. Interceptor / Service executes atomic `SETNX idempotency:<key> "PROCESSING" EX 120`.
3. If `SETNX` returns 0 (already exists), read `GET idempotency:res:<key>`. If present, return cached payload immediately.
4. If `SETNX` returns 1 (new), proceed inside `@Transactional` PostgreSQL boundary.
5. On commit, store response JSON via `SET idempotency:res:<key> <payload> EX 86400`.

### Solution 4.2: Field-Level HIPAA Redaction
Declare confidential fields as nullable: `clinicalDiagnosis: String`. In the resolver:
```java
@SchemaMapping(typeName = "Encounter", field = "clinicalDiagnosis")
public String clinicalDiagnosis(EncounterDto encounter, Authentication auth) {
    if (auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_DOCTOR"))) {
        return encounter.diagnosisNotes();
    }
    return null; // Gracefully redacted without failing query
}
```

### Solution 4.3: Mitigating Recursive DoS Attacks
Register `MaxQueryDepthInstrumentation` in `@Configuration`:
```java
@Bean
public Instrumentation maxQueryDepthInstrumentation() {
    return new MaxQueryDepthInstrumentation(7); // Rejects any AST nesting deeper than 7 levels
}
```

---

## SOLUTIONS FOR SECTION 5: Capstone Mini-Project Solution

### Complete Working Digital Wallet Service

#### 1. SDL Schema (`wallet.graphqls`)
```graphql
type Query {
    wallet(id: ID!): Wallet
}

type Mutation {
    transferFunds(input: TransferFundsInput!): TransferFundsPayload!
}

type Wallet {
    id: ID!
    accountNumber: String!
    balance: BigDecimal!
    currencyCode: String!
    transactions(first: Int = 10, after: String): TransactionConnection!
}

type TransactionConnection {
    edges: [TransactionEdge!]!
    pageInfo: PageInfo!
}

type TransactionEdge {
    node: Transaction!
    cursor: String!
}

type Transaction {
    id: ID!
    amount: BigDecimal!
    type: TransactionType!
    referenceNote: String
    createdAt: String!
}

enum TransactionType { CREDIT, DEBIT }

input TransferFundsInput {
    fromWalletId: ID!
    toWalletId: ID!
    amount: BigDecimal!
    referenceNote: String
}

type TransferFundsPayload {
    success: Boolean!
    transactionId: ID
    errorMessage: String
}
```

#### 2. Atomic Transfer Service Implementation
```java
package com.upskill.graphql.wallet.service;

import com.upskill.graphql.wallet.dto.WalletDtos.TransferFundsInput;
import com.upskill.graphql.wallet.dto.WalletDtos.TransferFundsPayload;
import com.upskill.graphql.wallet.entity.WalletEntity;
import com.upskill.graphql.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class WalletTransferService {

    private final WalletRepository walletRepository;

    public WalletTransferService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TransferFundsPayload executeTransfer(TransferFundsInput input) {
        if (input.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return new TransferFundsPayload(false, null, "Transfer amount must be positive");
        }

        WalletEntity sender = walletRepository.findById(input.fromWalletId())
                .orElse(null);
        WalletEntity receiver = walletRepository.findById(input.toWalletId())
                .orElse(null);

        if (sender == null || receiver == null) {
            return new TransferFundsPayload(false, null, "One or both wallets not found");
        }

        if (sender.getBalance().compareTo(input.amount()) < 0) {
            return new TransferFundsPayload(false, null, "Insufficient funds in source wallet");
        }

        // Deduct from sender and add to receiver
        sender.setBalance(sender.getBalance().subtract(input.amount()));
        receiver.setBalance(receiver.getBalance().add(input.amount()));

        walletRepository.save(sender);
        walletRepository.save(receiver);

        return new TransferFundsPayload(true, "TXN-" + System.currentTimeMillis(), null);
    }
}
```
