# Spring Boot and Annotation Guide — Explained Through This Project

## 1. What Spring Boot is doing for you

Spring Framework supplies dependency injection, MVC, transactions, data access, security, and more. Spring Boot adds:

- opinionated defaults;
- auto-configuration based on classpath and properties;
- starter dependency sets;
- embedded servers;
- production features such as Actuator;
- externalized configuration;
- a standard executable application model.

Spring Boot does not replace Spring. It configures Spring infrastructure so you write domain code rather than boilerplate wiring.

## 2. IoC and Dependency Injection

### Without a container

```java
PaymentRepository repo = new PaymentRepositoryImpl(...);
TicketServiceClient client = new TicketServiceClientImpl(...);
PaymentProcessor processor = new SimulatedPaymentProcessor(...);
PaymentService service = new PaymentServiceImpl(repo, client, processor, ...);
```

Every caller must know construction details.

### With Spring

```java
@Service
@RequiredArgsConstructor
class PaymentServiceImpl {
    private final PaymentRepository paymentRepository;
    private final TicketServiceClient ticketServiceClient;
    private final PaymentProcessor paymentProcessor;
}
```

Spring discovers/creates the beans and supplies constructor arguments. This is **Inversion of Control**: construction/wiring control is inverted from application code to the container.

Constructor injection is preferred because:

- dependencies are explicit;
- fields can be final;
- object cannot exist in an invalid partially injected state;
- unit tests can call the constructor;
- circular dependencies are exposed early.

`@Autowired` is unnecessary on a single constructor. This project mostly uses Lombok-generated constructors.

## 3. Bean lifecycle in this project

1. Spring scans packages below each main application class.
2. It reads configuration and auto-configuration conditions.
3. It registers bean definitions.
4. It instantiates dependencies in order.
5. Constructors validate required secrets/config values.
6. Post-processors create proxies for repositories, transactions, method security, Feign, etc.
7. Web server starts after the context is ready.
8. Singleton beans serve many requests, so they must not store request-specific mutable state in fields.

## 4. Auto-configuration examples

| Classpath/property condition | Boot behavior |
|---|---|
| MVC starter | Configure DispatcherServlet, JSON converters, validation, Tomcat. |
| Gateway WebFlux starter | Configure reactive Gateway/Netty pipeline. |
| JPA + datasource | Configure DataSource, EntityManagerFactory, Hibernate, transactions. |
| Flyway | Run migrations before JPA validation. |
| Security starter | Create security infrastructure; project supplies filter chain. |
| Eureka client starter | Create discovery client and registration behavior. |
| OpenFeign + enable annotation | Scan interfaces and create client proxies. |
| Actuator | Register operational endpoints, expose only configured ones. |

Auto-configuration “backs off” when you provide your own bean, such as a `SecurityFilterChain`.

## 5. MVC request lifecycle

Business services use servlet MVC:

```text
Tomcat connector
 -> servlet filters
 -> Spring Security FilterChain
 -> DispatcherServlet
 -> handler mapping chooses controller method
 -> argument resolution/deserialization
 -> Bean Validation
 -> controller
 -> service proxy/transaction
 -> repository/Feign
 -> return value converted by Jackson
```

Gateway uses WebFlux:

```text
reactive HTTP server
 -> WebFilter/SecurityWebFilterChain
 -> Gateway route predicates/filters
 -> nonblocking downstream exchange
 -> Mono completion
```

Do not mix servlet filter APIs into Gateway or blocking work into event-loop code.

## 6. Configuration and `.env` deeply explained

### YAML basics

Indentation creates nested keys:

```yaml
spring:
  datasource:
    username: ${AUTH_DB_USERNAME}
```

becomes property `spring.datasource.username`.

Lists use `-`:

```yaml
allowedMethods:
  - GET
  - POST
```

### Placeholders

```yaml
port: ${AUTH_SERVICE_PORT:9091}
username: ${AUTH_DB_USERNAME}
```

- first has a default;
- second is required and fails when unresolved.

### Why `.env` works here

Spring Boot does not universally treat `.env` as a built-in source. Every service explicitly imports:

```yaml
spring:
  config:
    import:
      - "optional:file:.env[.properties]"
      - "optional:file:../.env[.properties]"
```

- `optional` means missing file is allowed;
- `.env[.properties]` forces Java-properties parsing;
- relative paths use process working directory;
- service-folder run finds parent `backend/.env`;
- backend working-directory run finds `backend/.env` directly.

Variable names must match exactly. `4JWT_SECRET` does not satisfy `${JWT_SECRET}`.

### Simplified property precedence

Higher-precedence sources override lower ones. Relevant practical order includes:

1. command-line arguments/system properties;
2. OS/process environment variables;
3. imported external config such as `.env`;
4. packaged `application.yml`;
5. defaults after `:` in placeholders.

Exact Boot precedence has more layers, but the operational lesson is: an OS variable can override a `.env` value, and both can override YAML defaults.

### `application.yml` versus `bootstrap.yml`

This project has only `application.yml`. `bootstrap.yml` was historically used by Spring Cloud for a parent/bootstrap context, especially to contact Config Server before normal configuration. Modern Config Data imports can often use `application.yml`. Do not add bootstrap files unless a chosen configuration technology requires them.

### Typed configuration improvement

Instead of scattered `@Value`:

```java
@ConfigurationProperties(prefix = "otp")
@Validated
public record OtpProperties(
        @Positive int expiryMinutes,
        @Positive int maxAttempts,
        @PositiveOrZero int resendCooldownSeconds) {}
```

Benefits: one coherent type, metadata, validation, easier testing, fewer string keys.

## 7. Persistence and transactions

### Entity states

- transient: new object, not managed;
- managed: attached to persistence context; dirty changes can be flushed;
- detached: context ended;
- removed: scheduled for deletion.

### Dirty checking

Inside a transaction, changing a managed entity may persist even without an explicit `save`. The code often calls `save` anyway for clarity.

### Transaction proxy

`@Transactional` is usually implemented by a Spring proxy:

```text
caller -> transaction proxy -> begin -> target method -> commit/rollback
```

Important implications:

- self-invocation within the same object may bypass proxy advice;
- unchecked exceptions roll back by default;
- remote HTTP/provider effects are not part of the DB transaction;
- long transactions hold connections/locks;
- `readOnly=true` is a hint/optimization and clear intent.

### Isolation levels

| Level | Main idea |
|---|---|
| READ_COMMITTED | No dirty reads; repeated queries can change. |
| REPEATABLE_READ | Re-read rows stay stable; DB-specific phantom handling. |
| SERIALIZABLE | Behaves closest to serial execution, least concurrency. |

Ticket booking and Payment use SERIALIZABLE, but this does not make cross-service/provider operations atomic.

### Optimistic locking

`@Version` on Ticket, Payment, ServiceReport makes updates include the old version:

```sql
UPDATE ... SET ..., version=version+1
WHERE id=? AND version=?
```

Zero affected rows means another transaction changed it. Catch/translate optimistic-lock exceptions appropriately.

### Flyway versus Hibernate

- Flyway applies ordered, versioned SQL.
- Hibernate `ddl-auto=validate` verifies mapping/schema compatibility.
- Avoid `ddl-auto=update` for controlled production evolution.

## 8. Spring Security mental model

### Authentication

Who are you? Represented by `Authentication` in `SecurityContextHolder`.

### Authorization

What may you do? URL rules, method `@PreAuthorize`, and domain ownership checks.

### This project's trust chain

```text
Auth signs JWT
 -> Gateway verifies JWT
 -> Gateway strips forged headers
 -> Gateway adds ID/role/gateway secret
 -> downstream verifies secret
 -> downstream builds Authentication
 -> method role check
 -> service ownership check
```

### Stateless sessions

`SessionCreationPolicy.STATELESS` means Security does not store Authentication in an HTTP session. Each request must carry sufficient trusted context.

### CSRF and CORS

- CSRF protects against unwanted actions using automatically attached browser credentials. Bearer-token APIs commonly disable it; revisit if using cookies.
- CORS is a browser origin policy, not authentication. Non-browser callers ignore it.

## 9. Discovery, Feign, load balancing, resilience

### Feign

```java
@FeignClient(name = "route-service")
interface RouteClient {
    @GetMapping("/api/routes/{id}")
    RouteDto get(@PathVariable Long id);
}
```

Spring creates a proxy that:

1. resolves `route-service` through discovery;
2. load-balances an instance;
3. constructs HTTP request;
4. serializes parameters;
5. executes with configured timeout;
6. deserializes response or throws.

### Circuit breaker

Protects callers from repeatedly waiting on a failing dependency. It is not a correctness fallback and not a replacement for timeout.

### Fallback

Ticket's fallbacks translate outage to 503. Returning fabricated fare/capacity would be dangerous.

### Retry

Use bounded exponential backoff/jitter only for transient failures and safe/idempotent operations. A timeout does not prove the remote effect did not happen.

## 10. Annotation glossary — application/configuration

### `@SpringBootApplication`

Combines:

- `@Configuration`;
- `@EnableAutoConfiguration`;
- `@ComponentScan`.

Place main class above project packages so scanning sees them.

### `@Configuration`

Marks a class containing bean definitions. Examples: SecurityConfig, FeignConfig, LocaleConfig.

### `@Bean`

Registers the returned object as a Spring bean. Used for password encoder, security chain, Feign interceptor, CORS filter, MessageSource.

### `@Value("${...}")`

Injects one resolved property. Constructor injection is safer than mutable field injection. Prefer typed configuration for groups.

### `@Component`

Generic scanned bean. Used for filters, validators, mappers/utilities, simulated processor.

### `@Service`

Specialized component documenting business/service-layer intent. It does not automatically create a transaction; `@Transactional` does.

### `@Repository`

Persistence stereotype and exception-translation signal. Spring Data repository interfaces do not strictly need it when discovered.

### `@Primary`

Selects the default bean when multiple candidates implement one interface. `ConsoleOtpSender` is primary.

### `@Autowired`

Requests dependency injection. Not needed on a single constructor. Prefer constructor injection over field injection.

## 11. Discovery/client annotations

### `@EnableEurekaServer`

Turns Eureka application into registry server.

### `@EnableDiscoveryClient`

Explicitly activates discovery abstraction; used by Ticket. With modern starters it is often optional.

### `@EnableEurekaClient`

Historically Eureka-specific client activation. It is not used in this project; client starter/auto-configuration is sufficient. `@EnableDiscoveryClient` is vendor-neutral when explicit annotation is desired.

### `@EnableFeignClients`

Scans Feign interfaces and creates proxies. Used by Vehicle, Ticket, Payment, Analytics.

### `@FeignClient`

Declares a logical HTTP client name, optional config, fallback, and URL. Project uses discovery names rather than fixed URLs.

## 12. Web annotations

### `@RestController`

Combines `@Controller` and response-body serialization. Return DTO becomes JSON.

### `@RequestMapping`

Defines common path and/or method/metadata. Also used to accept both PUT and PATCH in one method.

### `@GetMapping`, `@PostMapping`, `@PatchMapping`, `@DeleteMapping`

Shortcut request mappings for HTTP verbs. POST creates/processes, GET reads, PATCH changes, DELETE cancels/deactivates.

### `@RequestBody`

Deserialize JSON body into DTO.

### `@PathVariable`

Bind a path segment such as `{ticketId}`.

### `@RequestParam`

Bind query parameter such as `?from=...`.

### `@RequestHeader`

Bind headers such as `Idempotency-Key` or internal service key.

### `@ResponseStatus`

Sets response status; booking/payment/report creation use 201.

### `@DateTimeFormat`

Controls textual parsing for query/path date/time values; ISO date means `yyyy-MM-dd`.

### `@Valid`

Triggers Jakarta Bean Validation on nested request object.

### `@RestControllerAdvice`

Global controller exception interception plus JSON response behavior.

### `@ExceptionHandler`

Maps one/more exception types to a handler method/HTTP response.

## 13. Security annotations

### `@EnableWebSecurity`

Enables servlet security configuration. Some services omit it because Boot/config bean is sufficient; explicit use documents intent.

### `@EnableWebFluxSecurity`

Reactive counterpart used by Gateway.

### `@EnableMethodSecurity`

Activates annotations such as `@PreAuthorize`.

### `@PreAuthorize`

Evaluates expression before method invocation:

```java
@PreAuthorize("hasAnyRole('TRANSPORT_MANAGER','ADMIN')")
```

`hasRole('ADMIN')` looks for authority `ROLE_ADMIN`.

### `@Order`

Controls component/filter/error-handler precedence. Lower values have higher priority in many Spring ordered chains; understand each API's contract.

## 14. JPA annotations

### `@Entity` / `@Table`

Marks persistent class and table metadata, including `@Index`/`@UniqueConstraint`.

### `@Id` / `@GeneratedValue`

Primary key and database identity generation.

### `@Column`

Column name/nullability/length/precision/scale/updatability/uniqueness mapping. JPA metadata is not a replacement for Flyway constraints.

### `@Enumerated(EnumType.STRING)`

Stores enum names. Safer than ordinals when order changes.

### `@OneToMany` / `@ManyToOne`

Entity relationships. Route owns lists of Stops/Schedules; children reference Route. Vehicle assignments/locations reference Vehicle.

### `@JoinColumn`

Defines FK column on owning relationship side.

### `@OrderBy`

Orders loaded child collection by entity property.

### `@JsonBackReference`

Jackson recursion control on child-to-parent serialization. DTOs remain preferable.

### `@PrePersist` / `@PreUpdate`

Entity lifecycle callbacks used for timestamps/defaults.

### `@CreationTimestamp` / `@UpdateTimestamp`

Hibernate-specific automatic timestamps used in Auth entities.

### `@Version`

Optimistic-lock field.

### `@Transactional`

Defines transaction boundary. Most services use Spring's annotation; Auth imports Jakarta's version. Spring supports both, but its own annotation exposes isolation/readOnly/propagation features used elsewhere.

### `@Query` / `@Param`

Custom JPQL query and named-parameter binding. JPQL uses entity/property names, not table/column names.

## 15. Validation annotations

| Annotation | Meaning/project example |
|---|---|
| `@NotNull` | Value must exist; route ID/status. |
| `@NotBlank` | Non-null string with non-whitespace; name/mobile. |
| `@Size` | String/collection size; names/passwords. |
| `@Pattern` | Regex; mobile, OTP, password, language. |
| `@Email` | Email syntax. |
| `@Positive` | Numeric > 0; identifiers/order. |
| `@Min` / `@Max` | Inclusive numeric bounds; passengers/capacity. |
| `@DecimalMin` / `@DecimalMax` | Decimal bounds; coordinates/fare/speed. |
| `@Digits` | Maximum integer/fraction digits; GPS and payment amount. |
| `@FutureOrPresent` | Date today or later; service date. |

Field constraints cannot express every cross-field rule. Service logic checks source before destination, arrival after departure, ownership, matching amount, etc. Custom class-level validators are another option.

## 16. Lombok annotations

- `@Getter` / `@Setter`: generate accessors.
- `@Data`: getters, setters, equals/hashCode/toString, required constructor. Use cautiously on JPA entities.
- `@NoArgsConstructor` / `@AllArgsConstructor`: constructors.
- `@RequiredArgsConstructor`: constructor for final/non-null fields, enabling concise injection.
- `@Builder`: builder API.
- `@Builder.Default`: preserve initializer when builder omits field.
- `@Slf4j`: static SLF4J logger.
- Lombok `@NonNull`: generated null checks/metadata; different from JSpecify `@NonNull` and validation `@NotNull`.

Lombok runs as an annotation processor during compilation, explaining the compiler plugin configuration.

## 17. Java/other annotations

- `@Override`: compiler verifies method overrides/implements a parent declaration.
- `@FunctionalInterface`: ensures exactly one abstract method, used by Payment callback lambda.
- JSpecify `@NonNull`: static nullness metadata, not runtime request validation.

## 18. Testing annotations

- `@Test`: JUnit test method.
- `@BeforeEach` / `@AfterEach`: setup/cleanup around each test.
- `@ExtendWith`: integrate JUnit extension such as Mockito.
- `@Mock`: Mockito test double.
- `@SpringBootTest`: full Boot application context; optional random web port.
- `@LocalServerPort`: inject random test server port.
- `@DataJpaTest`: focused JPA slice, transactional rollback by default, often embedded DB.

Testing pyramid:

```text
few end-to-end tests
more integration/contract tests
many fast service/unit tests
```

H2 is fast but not identical to MySQL. Use Testcontainers for migrations, enum/locking/index/concurrency behavior.

## 19. HTTP status guide for this project

| Status | Meaning |
|---:|---|
| 200 | Successful read/update/action. |
| 201 | Resource/attempt/report created. |
| 400 | Invalid syntax/validation/domain input. |
| 401 | Missing/invalid authentication/trusted identity. |
| 403 | Authenticated but forbidden/other-owner/internal key invalid. |
| 404 | Requested entity/resource missing. |
| 405 | HTTP method unsupported. |
| 409 | Duplicate, state, seat, idempotency, concurrency conflict. |
| 422 | Valid request but processor could not complete refund in current Payment mapping. |
| 429 | OTP resend rate limit. |
| 503 | Required microservice/source unavailable. |

## 20. Common beginner mistakes and corrections

- “Dependency in POM means IntelliJ must resolve it” → IDE still needs a correct Maven module/import model.
- “`.env` loads automatically” → this project explicitly imports it, and working directory/names matter.
- “`@Service` starts a transaction” → only transaction annotation/proxy does.
- “`saveAndFlush` commits” → flush sends SQL; commit happens at transaction end.
- “Feign call is like a local method” → it can timeout, duplicate, partially succeed, and deserialize bad contracts.
- “Gateway role check is enough” → owning service must enforce resource ownership/state.
- “CORS secures API” → browser policy only.
- “Serializable DB transaction covers other services” → it never covers remote systems.
- “Fallback should return something” → only return a fallback when it is semantically safe.
- “Entity should be API response” → use DTOs to isolate persistence/security/contracts.
- “Validation constraint replaces DB constraint” → application validation improves UX; DB constraint guarantees under concurrency.

## 21. Spring Boot interview quick answers

### What is auto-configuration?

Conditional configuration activated by classpath, properties, and existing beans. It supplies sensible infrastructure defaults and backs off when application configuration takes control.

### `@Component` versus `@Service` versus `@Repository`?

All are components. Specialized stereotypes communicate layer intent; Repository also participates in persistence exception translation.

### Why constructor injection?

Explicit immutable dependencies, easier tests, no partially initialized bean, clearer circular-dependency failures.

### What is a proxy in Spring?

A wrapper object that intercepts calls to add behavior such as transactions, method authorization, repositories, or Feign HTTP execution.

### What is starter dependency?

A curated dependency descriptor for one capability, designed to work with Boot's dependency management and auto-configuration.

### Why externalized configuration?

Same artifact can run in multiple environments with different ports/credentials/policies, while secrets stay outside source.

### What causes circular dependency?

Bean A requires B and B requires A. It often signals mixed responsibilities; refactor rather than relying on field/lazy workarounds.

### Why Actuator?

Operational health, info, metrics, and diagnostics. Expose only needed endpoints and secure sensitive details.
