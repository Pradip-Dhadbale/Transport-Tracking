# Smart Transport Management Backend — Master Study Guide Index

## Purpose of this guide

This study guide explains the real eight-service backend in a learning order that follows the runtime dependency graph. It is designed for a beginner who needs to understand what every package and file does, while also preparing an experienced developer for design discussions and interviews.

The guide always answers four questions:

1. **What does this code do?**
2. **Why is it designed this way?**
3. **How does Spring Boot make it work?**
4. **How does it connect to the rest of the system?**

## Recommended reading order

1. Shared architecture and Spring Boot foundation
2. Eureka Server — service discovery
3. API Gateway — the single external entry point
4. Auth Service — identity, JWT, OTP, roles, and profiles
5. Route Service — routes, stops, schedules, and fare calculation
6. Vehicle Service — fleet, assignments, availability, and tracking
7. Ticket Service — booking orchestration and ticket lifecycle
8. Payment Service — idempotent payment and refund coordination
9. Analytics Service — cross-service aggregation and persisted reports
10. End-to-end flows, operations, testing, pitfalls, and interview revision

This order is deliberate. Eureka and Gateway explain the infrastructure. Auth explains the trusted identity headers. Route owns reference data used by Vehicle and Ticket. Ticket coordinates Route and Vehicle. Payment coordinates with Ticket. Analytics reads from Route, Vehicle, Ticket, and Payment.

---

## Part I — System-wide foundation

### 1. Repository and build structure

- The eight Maven projects and the backend reactor `pom.xml`
- Why each service has its own `pom.xml`, source tree, database, and port
- Maven coordinates: `groupId`, `artifactId`, version, packaging, parent, and BOM
- Spring Boot parent versus Spring Cloud dependency management
- Starter dependencies and transitive dependencies
- Maven wrapper files (`mvnw`, `mvnw.cmd`)
- `src/main/java`, `src/main/resources`, and `src/test/java`
- Compile, test, package, and run lifecycles
- Java 17 target versus the installed JDK

### 2. Runtime architecture

- Clients enter through API Gateway on port 9090
- Service discovery through Eureka on port 8761
- Auth 9091, Route 9092, Ticket 9093, Vehicle 9094, Payment 9095, Analytics 9096
- One database/schema per business service
- Synchronous OpenFeign calls and client-side load balancing
- Circuit breakers, fallbacks, timeouts, and failure translation
- Trusted identity headers propagated by the gateway
- Separate internal service-secret headers
- Why internal analytics and payment endpoints are not public APIs

### 3. Interconnection map

```text
Browser / React client
        |
        v
API Gateway -- validates JWT and adds trusted identity headers
   |       |       |        |         |          |
   v       v       v        v         v          v
 Auth    Route   Ticket   Vehicle   Payment   Analytics
                  |  \       |        |          | | | \
                  |   \------|        |          | | |  +--> Route
                  |                  Ticket <-----+ | +-----> Vehicle
                  +--> Route                     | +-------> Ticket
                  +--> Vehicle                   +---------> Payment

All discoverable services <----------------------------> Eureka
```

### 4. Request lifecycle shared by business services

```text
HTTP request
  -> Gateway CORS/security/filter chain
  -> Eureka-based route selection
  -> Service security filter chain
  -> trusted-header or internal-secret validation
  -> Controller
  -> DTO validation
  -> Service interface and implementation
  -> Repository / Feign client / domain processor
  -> Mapper
  -> response DTO and ApiResponseDto
  -> global exception handler if anything fails
```

### 5. Configuration and `.env` foundation

- YAML indentation, maps, lists, scalars, and relaxed binding
- `${VARIABLE}` versus `${VARIABLE:default}`
- `spring.config.import` and `.env[.properties]`
- Why Spring Boot does not normally load `.env` automatically
- Working-directory-dependent file resolution
- Environment-variable precedence over YAML and imported files
- Secret handling and why credentials should not be committed
- Required properties versus dangerous development defaults
- Per-service datasource, JPA, Flyway, Eureka, Feign, resilience, management, and logging configuration
- No `bootstrap.yml` is currently present; when bootstrap configuration would be relevant

### 6. Core Spring and Spring Boot concepts

- Inversion of Control and Dependency Injection
- Component scanning and the application context
- Beans, bean lifecycle, scopes, constructor injection, and immutability
- Auto-configuration and conditional configuration
- MVC versus WebFlux (Gateway is reactive; business services are servlet/MVC)
- Embedded Tomcat and the reactive gateway server
- DTO validation and Jakarta Bean Validation
- Spring Data JPA repository proxies and derived queries
- Hibernate entity state, dirty checking, transactions, and optimistic locking
- Flyway migrations versus Hibernate schema generation
- Spring Security filter chains and method security
- OpenFeign declarative HTTP clients
- Eureka discovery and logical service names
- Resilience4j circuit breakers
- Localization with `MessageSource` and locale resolvers
- Actuator health and diagnostic endpoints

### 7. Shared annotation catalog

- Application/configuration: `@SpringBootApplication`, `@Configuration`, `@Bean`, `@Value`
- Dependency injection: `@Component`, `@Service`, `@Repository`, `@RequiredArgsConstructor`, `@Autowired`, `@Primary`
- Web MVC: `@RestController`, `@RequestMapping`, mapping annotations, request binding annotations, `@Valid`, `@ResponseStatus`
- Security: `@EnableWebSecurity`, `@EnableWebFluxSecurity`, `@EnableMethodSecurity`, `@PreAuthorize`, `@Order`
- Discovery/clients: `@EnableEurekaServer`, `@EnableDiscoveryClient`, `@EnableFeignClients`, `@FeignClient`
- Persistence: `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, relationships, `@Column`, `@Enumerated`, timestamps, callbacks, `@Version`, `@Transactional`, `@Query`, `@Param`
- Validation: `@NotNull`, `@NotBlank`, `@Size`, `@Pattern`, `@Email`, numeric constraints, date constraints
- Exceptions: `@RestControllerAdvice`, `@ExceptionHandler`
- Lombok: `@Data`, `@Getter`, `@Setter`, `@Builder`, constructor annotations, `@Slf4j`
- Testing: `@Test`, `@SpringBootTest`, `@DataJpaTest`, `@Mock`, `@ExtendWith`, lifecycle annotations
- Serialization/helpful metadata: `@JsonBackReference`, `@DateTimeFormat`, `@FunctionalInterface`

---

## Part II — Service chapters

Every service chapter uses the same mentor-style template so no layer is skipped:

1. Role and business boundary
2. Position in the architecture and dependencies
3. Directory/file map
4. `pom.xml` dependency explanation
5. Main application class and startup sequence
6. `application.yml` line-by-line explanation
7. Controllers and endpoint contract
8. Request/response DTOs and validation
9. Services and business rules
10. Entities, relationships, and lifecycle callbacks
11. Repositories, derived queries, projections, and custom queries
12. Mappers and boundary isolation
13. Configuration and security filters
14. Feign clients, resilience, and internal authentication
15. Exceptions and error response format
16. Database migrations line by line
17. Tests and what each test proves
18. Complete request-flow walkthroughs
19. Spring Boot concepts beside the project code
20. Best practices, pitfalls, and improvement opportunities
21. Interviewer favorite questions with model answers

### Chapter 1 — Eureka Server (port 8761)

#### Files covered

- `pom.xml`, Maven wrappers
- `TransportSysProjectApplication.java`
- `application.yml`
- context-load test

#### Deep topics

- Service registry versus API Gateway
- `@EnableEurekaServer`
- Registration, heartbeat, lease, eviction, and registry lookup
- Why `register-with-eureka` and `fetch-registry` are false for the server
- Hostname and `defaultZone` construction
- Self-preservation behavior and local-development trade-offs
- What happens when Eureka is unavailable
- Why newer clients often do not require `@EnableEurekaClient`

#### Interview focus

- Client-side versus server-side discovery
- Eureka high availability
- Stale registrations and heartbeats
- Discovery name casing and `lb://` URIs

### Chapter 2 — API Gateway (port 9090)

#### Files covered

- `pom.xml`, Maven wrappers
- `ApiGatewayApplication.java`
- `CorsConfig.java`, reactive `SecurityConfig.java`
- `JwtAuthFilter.java`, `LoggingFilter.java`
- `ErrorResponseDto.java`, `GlobalExceptionHandler.java`
- `application.yml`
- CORS and JWT filter tests

#### Deep topics

- Reactive Spring Cloud Gateway versus MVC services
- Route predicates, `lb://` URIs, discovery, and route ordering
- Public Auth endpoints versus protected business endpoints
- JWT signature/expiry validation at the edge
- Trusted headers (`X-User-Id`, role, language) and spoofing prevention
- Gateway shared secret and downstream trust boundary
- Global filters, filter ordering, request mutation, and correlation IDs
- CORS preflight handling
- Reactive security and why servlet filters cannot be reused here

#### Interconnections

- Routes external requests to Auth, Route, Ticket, Vehicle, Payment, and Analytics
- Uses Eureka to resolve logical service names
- Creates the identity context consumed by downstream header filters

#### Interview focus

- Gateway versus load balancer
- Authentication at gateway versus authorization in services
- CORS, CSRF, rate limiting, retries, and token propagation
- How to prevent clients from forging internal headers

### Chapter 3 — Auth Service (port 9091)

#### Files covered

- `pom.xml`, Maven wrappers, service README
- `AuthServiceApplication.java`
- Config: `AppConfig`, `JwtLocaleResolver`, `LocaleConfig`, `SecurityConfig`
- Controller: `AuthController`
- All request and response DTOs
- Entities: `User`, `OtpVerification`, `RefreshToken`
- Enums: `Role`, `OtpPurpose`
- Repositories for users, OTP records, and refresh tokens
- Services: `AuthService`, `JwtService`, `OtpService`, sender interfaces and console implementations
- `MessageUtil`, request logging filter, authentication filter, entry point, access-denied handler
- All custom exceptions and global handler
- `application.yml`, Flyway V1/V2, English/Hindi message bundles
- JWT, OTP, auth-token, and exception tests

#### Deep flows

- Registration: send OTP → verify OTP → short registration token → complete profile
- OTP login and password login
- Access-token and refresh-token lifecycle
- Logout/revocation
- Forgot/reset password
- Profile, language, email verification, and password change
- Admin user listing, staff creation, role/status changes

#### Spring concepts beside the code

- Password encoding, JWT HMAC keys, stateless sessions
- Filter authentication and `SecurityContext`
- Method-level role checks
- Transactions spanning user/token/OTP updates
- Repository query methods and uniqueness
- Localization based on token/user language

#### Interview focus

- JWT versus session authentication
- Why refresh tokens are stored while access tokens are not
- OTP hashing, expiration, resend cooldown, and attempt limits
- 401 versus 403
- Secret length, rotation, replay prevention, and revocation

### Chapter 4 — Route Service (port 9092)

#### Files covered

- `pom.xml`, Maven wrappers, README
- `RouteServiceApplication.java`
- Controllers: `RouteController`, `StopController`, `ScheduleController`
- Request/response DTOs
- Entities: `Route`, `Stop`, `Schedule`
- `DayOfWeekEnum`, `ScheduleDays`
- Repositories for routes, stops, and schedules
- Service interfaces and four implementations including fare calculation
- `RouteMapper`
- Locale/security configuration and trusted/internal header filters
- All route-specific exceptions and global handler
- `application.yml`, Flyway V1/V2, English/Hindi messages
- Repository, security, fare, route, stop, and schedule tests

#### Deep flows

- Admin creates route → ordered stops → schedules
- Search by source, destination, travel date, and stop sequence
- Fare calculation between two stops
- Route/schedule lookup used internally by Vehicle, Ticket, and Analytics
- Update/delete rules and duplicate protection

#### Spring concepts beside the code

- Nested REST resources
- JPA one-to-many/many-to-one relationships
- Fetching, JSON recursion, ordering, and transaction boundaries
- Custom JPQL/native query trade-offs
- Locale-aware error messages

#### Interview focus

- Modeling ordered stops
- Avoiding N+1 queries
- PUT versus PATCH
- Money with `BigDecimal`
- Query indexes and route-search performance

### Chapter 5 — Vehicle Service (port 9094)

#### Files covered

- `pom.xml`, Maven wrappers, README
- `VehicleServiceApplication.java`
- `VehicleController`, `VehicleAnalyticsController`
- All request/response DTOs
- Entities: `Vehicle`, `VehicleAssignment`, `VehicleLocation`
- Enums: `VehicleStatus`, `AssignmentStatus`
- Three repositories and analytics projections
- Vehicle, assignment, tracking, analytics interfaces/implementations
- `RouteReferenceValidator`
- Route Feign client and Feign configuration
- Security configuration, trusted/internal filters, analytics key validator
- Mapper, exceptions, and global handler
- `application.yml`, Flyway V1
- Repository, filters, validation, tracking, assignment, analytics, and vehicle tests

#### Deep flows

- Fleet CRUD and status transitions
- Validate route/schedule through Route Service
- Create, complete, or cancel assignments
- Availability lookup used by Ticket Service
- Submit and query live/historical locations
- Internal assignment analytics

#### Spring concepts beside the code

- Declarative Feign clients and discovery-based calls
- Cross-service validation without cross-database foreign keys
- Transaction isolation, conflict checks, and where adding `@Version` would strengthen concurrent updates
- Time-range queries and projections
- Internal endpoint authorization

#### Interview focus

- Preventing overlapping vehicle assignments
- Eventual consistency when Route data changes
- GPS data scaling and retention
- Feign timeouts and circuit-breaker behavior

### Chapter 6 — Ticket Service (port 9093)

#### Files covered

- `pom.xml`, Maven wrappers, README
- `TicketServiceApplication.java`
- Controllers: passenger ticket, payment-internal, and analytics-internal
- All booking, payment, fare, availability, ticket, and analytics DTOs
- `Ticket` entity and `TicketStatus`
- Repository and analytics projections
- Booking and analytics service interfaces/implementations
- Route/Vehicle Feign clients and fallback factories
- Feign, locale, and security configuration
- Gateway, internal, payment, and analytics key validators/filters
- Request/response logging filter
- Mapper, ticket-number generator, booking-request hasher
- Exceptions and global handler
- `application.yml`, `logback-spring.xml`, Flyway V1–V4, message bundles
- Repository, payment lifecycle, authorization, booking, analytics, and security tests

#### Deep flows

- Book ticket with `Idempotency-Key`
- Validate fare through Route Service
- Validate assigned vehicle capacity through Vehicle Service
- Persist a pending-payment ticket
- Owner/admin ticket retrieval and cancellation
- Internal payment context and payment confirmation/failure/refund callbacks
- Internal analytics summaries

#### Spring concepts beside the code

- Orchestration and partial-failure handling
- Idempotency hashing and conflict detection
- Fallback factories versus ordinary exception handling
- State machines enforced in a service layer
- Structured logging and correlation

#### Interview focus

- Avoiding duplicate bookings
- Distributed transactions and Saga alternatives
- Seat-allocation race conditions
- Timeout/retry safety
- Why a fallback must not silently return incorrect business data

### Chapter 7 — Payment Service (port 9095)

#### Files covered

- `pom.xml`, Maven wrappers, README
- `PaymentServiceApplication.java`
- `PaymentController`, `PaymentAnalyticsController`
- Payment request/response, ticket context/update, and analytics DTOs
- `Payment` entity; `PaymentMethod`, `PaymentStatus`
- Repository and analytics projections
- Payment and analytics service interfaces/implementations
- Processor strategy: `PaymentProcessor`, `SimulatedPaymentProcessor`, `ProcessorResult`
- Ticket Feign client and Feign configuration
- Gateway/internal/analytics security components
- Mapper and `PaymentRequestHasher`
- Payment exceptions and global handler
- `application.yml`, Flyway V1
- Repository, filters, payment lifecycle, and analytics tests

#### Deep flows

- Start payment with `Idempotency-Key`
- Retrieve authoritative ticket payment context
- Check ownership, amount, currency, and ticket state
- Simulate provider success/failure
- Persist payment and notify Ticket Service
- Refund and ticket refund callback
- Internal payment analytics

#### Spring concepts beside the code

- Strategy pattern as Spring beans
- Idempotent external operations
- Optimistic locking and unique constraints
- Compensating actions when callbacks fail
- Circuit breakers and exception translation

#### Interview focus

- Exactly-once illusion versus at-least-once delivery
- Webhook security and production payment providers
- Refund idempotency
- PCI scope and sensitive-data handling
- Reconciling payment and ticket state

### Chapter 8 — Analytics Service (port 9096)

#### Files covered

- `pom.xml`, Maven wrappers, README
- `AnalyticsServiceApplication.java`
- `AnalyticsController`
- All usage, revenue, performance, report, error, and wrapper DTOs
- `ServiceReport` entity, repository, and mapper
- Analytics service interface/implementation
- Four Feign clients: Route, Ticket, Vehicle, Payment
- Feign and security configuration
- Trusted/internal header filters
- Analytics exceptions and global handler
- `application.yml`, Flyway V1
- Service, repository, exception, and security tests

#### Deep flows

- Route-usage aggregation from Ticket Service
- Revenue aggregation from Payment Service
- Performance report combining Vehicle assignments and Route schedules
- On-time grace calculation
- Generate, list, and retrieve persisted report snapshots
- Degraded behavior when a source service is unavailable

#### Spring concepts beside the code

- API composition and fan-out calls
- Read models, live aggregation, and stored snapshots
- Date/time range validation
- Projection DTOs and mapping
- Latency multiplication and resilience

#### Interview focus

- API composition versus event-driven analytics
- Fan-out failure and timeout budgets
- Data freshness and report reproducibility
- When to introduce a warehouse or stream platform

---

## Part III — End-to-end connected flows

### Flow A: Service startup and discovery

- Start Eureka
- Start business services and observe registration
- Start Gateway and resolve routes
- Readiness versus liveness
- Expected behavior during registry outages

### Flow B: Passenger registration and authenticated request

- OTP request and verification
- Registration token and completion
- JWT creation
- Gateway validation
- Trusted identity headers
- Downstream authorization

### Flow C: Admin prepares transport supply

- Create route
- Add ordered stops
- Add schedules
- Register vehicle
- Assign vehicle to route/schedule

### Flow D: Passenger searches and books

- Search route/schedule
- Gateway identity propagation
- Ticket requests fare from Route
- Ticket requests availability from Vehicle
- Idempotent ticket persistence

### Flow E: Passenger pays

- Payment requests ticket context
- Payment validates ownership/amount
- Processor result
- Payment persistence
- Ticket confirmation/failure callback
- Retry and reconciliation concerns

### Flow F: Refund

- Ownership/state checks
- Provider refund
- Payment state transition
- Ticket refund callback
- Duplicate refund protection

### Flow G: Admin analytics

- Gateway/admin authorization
- Analytics fan-out to Ticket, Payment, Vehicle, Route
- Aggregation and persisted report snapshot
- Source-service failure behavior

---

## Part IV — Database and persistence study

- Database-per-service rule
- Complete schema ownership matrix
- All Flyway migrations and evolution history
- Primary keys, natural keys, unique constraints, and indexes
- Cross-service IDs without foreign keys
- `BigDecimal` precision and currency
- Dates, timestamps, and time zones
- Entity validation versus database constraints
- Optimistic locking and concurrency
- Transaction isolation and race conditions
- Migration rollout and backward compatibility

## Part V — Security study

- Trust boundaries and threat model
- JWT signing and minimum HMAC key size
- Gateway shared secret
- Payment and Analytics internal secrets
- Identity header spoofing prevention
- Public, passenger, admin, and internal endpoint matrix
- 400/401/403/404/409/422/503 decisions
- CORS versus CSRF
- Password hashing and OTP handling
- Logging without leaking credentials/tokens
- Secret storage and rotation
- Production recommendations: TLS/mTLS, secret manager, key rotation, rate limits

## Part VI — Resilience and distributed-systems study

- Network calls are not local method calls
- Timeout budgets
- Circuit breaker states
- Fallback factories
- Retry safety and idempotency
- Duplicate delivery
- Partial failures
- Distributed state machines
- Compensation and reconciliation
- Service discovery failure modes
- Observability and correlation IDs
- When messaging would be preferable to synchronous callbacks

## Part VII — Testing study

- Unit tests with mocks
- Controller/filter/security tests
- `@DataJpaTest` integration tests with H2
- Full-context tests
- What the existing tests cover by service
- Missing test categories
- Contract testing for Feign clients
- Testcontainers for MySQL/Eureka integration
- End-to-end flow testing
- Failure injection and resilience testing

## Part VIII — Operations and troubleshooting

- Correct local startup order
- `.env` troubleshooting checklist
- Maven versus IntelliJ dependency model
- Port conflicts
- Database and Flyway failures
- Eureka registration problems
- Gateway 401/403/404/503 diagnosis
- Feign timeout/circuit-breaker diagnosis
- Locked `target` files on Windows
- Actuator health checks
- Logging/correlation workflow
- Safe production configuration checklist

## Part IX — Interview revision pack

- Service-specific favorite questions are included at the end of every chapter
- System-design questions for the full platform
- Spring Boot rapid revision
- Spring Security rapid revision
- JPA/Hibernate rapid revision
- Microservices/distributed-systems rapid revision
- Scenario questions: duplicate booking, payment callback failure, Eureka outage, stale route data, analytics source outage
- Common trick questions and concise model answers

## Part X — Reference appendices

- Endpoint matrix
- Service-to-service call matrix
- Port and service-name table
- Environment-variable catalog (secrets redacted)
- Annotation glossary
- HTTP status/error matrix
- Entity/table ownership matrix
- Startup and dependency graph
- Glossary of project terms
- Suggested improvements prioritized by risk and learning value

---

## Coverage checklist

The completed guide will account for:

- [x] All 8 service `pom.xml` files and the reactor POM
- [x] All 8 application entry points
- [x] All Java production files by name and responsibility
- [x] All application YAML files line by line
- [x] The absence and possible purpose of `bootstrap.yml`
- [x] All controllers and endpoints
- [x] All DTOs and validation constraints
- [x] All entities, enums, repositories, projections, and migrations
- [x] All service interfaces and implementations
- [x] All configuration, filters, security handlers, and Feign clients
- [x] All mappers, utilities, processors, exceptions, and error handlers
- [x] All test files and the behavior they verify
- [x] Every annotation used in production code
- [x] Cross-service and end-to-end flows
- [x] Spring Boot concepts alongside the concrete code
- [x] Best practices, pitfalls, common mistakes, and production improvements
- [x] Interviewer favorite questions and answers for every service
