# Exact File-by-File Coverage Catalog

This catalog is the accountability companion to the eight teaching chapters. Every source, configuration, migration, and test file is named here with its responsibility. “Request DTO” means inbound API data plus validation; “response DTO” means a stable outbound contract that prevents exposing JPA entities directly.

Generated/build artifacts such as `target/`, log files, and IDE metadata inside service folders are intentionally excluded because they are not application source.

## Backend root

| File | Responsibility |
|---|---|
| `pom.xml` | Maven reactor/aggregator that lists all eight modules so the whole backend can be imported or built as one project. |
| `.env` | Local runtime values and secrets. Spring reads it only because each service explicitly imports it; it must not be committed. |
| `.env.example` | Safe template showing required variable names and non-secret examples. |
| `README.md` | Project-level setup, service, and run instructions. |
| `.project` | Eclipse project metadata; it does not affect Spring runtime behavior. |

## 1. Eureka Server

Base folder: `eureka-server`

| File | Responsibility |
|---|---|
| `pom.xml` | Declares the Spring Boot application and Eureka Server dependency. |
| `TransportSysProjectApplication.java` | Starts Spring Boot and enables the Eureka service registry with `@EnableEurekaServer`. |
| `application.yml` | Sets port `8761`, the registry name, and disables self-registration/fetching because this process is the registry. |
| `TransportSysProjectApplicationTests.java` | Smoke test that verifies the Spring application context can start. |

## 2. API Gateway

Base folder: `api-gateway`

| File | Responsibility |
|---|---|
| `pom.xml` | Declares reactive Gateway, Eureka client, JWT, Actuator, validation, and test dependencies. |
| `ApiGatewayApplication.java` | Boot entry point and discovery-client application. |
| `config/CorsConfig.java` | Creates the global reactive CORS policy used before requests reach downstream services. |
| `config/SecurityConfig.java` | Defines the WebFlux `SecurityWebFilterChain` and which gateway paths are public or authenticated. |
| `dto/ErrorResponseDto.java` | Standard gateway error response shape. |
| `exception/GlobalExceptionHandler.java` | Converts gateway exceptions into consistent HTTP error responses. |
| `filter/JwtAuthFilter.java` | Validates bearer JWTs, strips spoofable headers, and adds trusted user/role/gateway-key headers. |
| `filter/LoggingFilter.java` | Logs request/response metadata in the reactive filter chain. |
| `application.yml` | Imports `.env`, configures port `9090`, Eureka, JWT/key values, CORS-related behavior, and every route predicate/filter. |
| `config/GatewayCorsIntegrationTest.java` | Integration test for preflight and CORS response behavior. |
| `filter/JwtAuthFilterTest.java` | Unit tests valid, invalid, absent JWT and trusted-header behavior. |

## 3. Auth Service

Base folder: `auth-service`

### Project, configuration, and entry point

| File | Responsibility |
|---|---|
| `pom.xml` | Declares MVC, Security, JPA, validation, JWT, MySQL, Flyway, Eureka, Actuator, Lombok, and tests. |
| `README.md` | Auth-specific setup, endpoint, and configuration notes. |
| `AuthServiceApplication.java` | Starts the service and registers it with discovery. |
| `config/AppConfig.java` | Defines shared application beans such as the password encoder. |
| `config/JwtLocaleResolver.java` | Selects locale from the authenticated JWT/user context when available. |
| `config/LocaleConfig.java` | Registers locale resolution and message-bundle configuration. |
| `config/SecurityConfig.java` | Defines stateless servlet security, public/authenticated paths, filters, handlers, and password encoding. |

### Web contracts and controller

| File | Responsibility |
|---|---|
| `controller/AuthController.java` | Exposes registration, OTP, login, refresh, password, profile, staff, role, and status endpoints. |
| `dto/request/ChangePasswordRequestDto.java` | Current/new-password input and validation. |
| `dto/request/CompleteRegistrationRequestDto.java` | Final registration data submitted after OTP verification. |
| `dto/request/CreateStaffRequestDto.java` | Admin request for creating staff accounts. |
| `dto/request/LoginWithPasswordRequestDto.java` | Email/phone identifier and password login input. |
| `dto/request/RefreshTokenRequestDto.java` | Refresh-token input used to obtain a new access token. |
| `dto/request/ResetPasswordRequestDto.java` | Verified reset token/OTP context and new password. |
| `dto/request/SendOtpRequestDto.java` | Recipient and OTP-purpose input. |
| `dto/request/UpdateLanguageDto.java` | Preferred-language update input. |
| `dto/request/UpdateProfileRequestDto.java` | Editable user-profile fields. |
| `dto/request/UpdateUserRoleRequestDto.java` | Admin role-change input. |
| `dto/request/UpdateUserStatusRequestDto.java` | Admin activation/deactivation input. |
| `dto/request/VerifyEmailOtpRequestDto.java` | Email OTP verification input. |
| `dto/request/VerifyOtpRequestDto.java` | General OTP verification input. |
| `dto/response/ApiResponseDto.java` | Generic successful response envelope. |
| `dto/response/AuthResponseDto.java` | Access token, refresh token, and authenticated-user response. |
| `dto/response/ErrorResponseDto.java` | Consistent auth error payload. |
| `dto/response/UserResponseDto.java` | Safe user view without password or token internals. |

### Domain and persistence

| File | Responsibility |
|---|---|
| `entity/User.java` | User aggregate containing identity, encoded password, role, status, language, and timestamps. |
| `entity/OtpVerification.java` | Stores hashed OTP challenge, purpose, expiry, attempts, and verification state. |
| `entity/RefreshToken.java` | Stores refresh-token lifecycle and user association. |
| `enums/OtpPurpose.java` | Restricts OTP use to supported flows such as registration or password reset. |
| `enums/Role.java` | Defines application authorities such as passenger, staff, and admin. |
| `repository/UserRepository.java` | User lookup, existence, and persistence queries. |
| `repository/OtpVerificationRepository.java` | Queries the current OTP challenge and cleans/replaces OTP records. |
| `repository/RefreshTokenRepository.java` | Finds, revokes, and deletes refresh tokens. |

### Services, security, utilities, and errors

| File | Responsibility |
|---|---|
| `service/AuthService.java` | Main authentication and user-management use cases; this project implements them in the concrete class itself. |
| `service/JwtService.java` | Generates, parses, validates, and extracts claims from access tokens. |
| `service/OtpService.java` | Generates secure OTPs, enforces rate/attempt/expiry rules, hashes values, and verifies challenges. |
| `service/OtpSender.java` | Abstraction for delivering OTP messages. |
| `service/ConsoleOtpSender.java` | Development OTP sender that writes delivery information to logs/console. |
| `service/EmailSender.java` | Abstraction for email delivery. |
| `service/ConsoleEmailSender.java` | Development email sender; a production implementation would call an email provider. |
| `security/JwtAuthenticationFilter.java` | Reads and validates bearer JWTs and populates Spring Security’s authentication context. |
| `security/CustomAuthenticationEntryPoint.java` | Returns structured `401 Unauthorized` responses. |
| `security/CustomAccessDeniedHandler.java` | Returns structured `403 Forbidden` responses. |
| `filters/RequestLoggingFilter.java` | Logs inbound servlet requests without changing business behavior. |
| `util/MessageUtil.java` | Resolves localized text from message bundles. |
| `exception/GlobalExceptionHandler.java` | Maps domain, validation, and unexpected exceptions to HTTP responses. |
| `exception/DuplicateResourceException.java` | Signals duplicate email/phone or another unique resource. |
| `exception/InvalidCredentialsException.java` | Signals failed password authentication. |
| `exception/InvalidOtpException.java` | Signals an incorrect, expired, used, or mismatched OTP. |
| `exception/InvalidTokenException.java` | Signals an invalid, expired, or revoked token. |
| `exception/OtpAttemptExceededException.java` | Signals too many verification attempts. |
| `exception/OtpRateLimitException.java` | Signals OTP resend/request throttling. |
| `exception/ResourceNotFoundException.java` | Signals that a requested user/token/resource does not exist. |

### Resources and tests

| File | Responsibility |
|---|---|
| `application.yml` | Imports `.env`; configures `9091`, datasource/JPA/Flyway, Eureka, JWT, OTP, logging, Actuator, and application keys. |
| `messages.properties` | Default localized auth and validation messages. |
| `messages_hi.properties` | Hindi translations for the same message keys. |
| `db/migration/V1__create_auth_schema.sql` | Creates the initial user, OTP, and refresh-token schema. |
| `db/migration/V2__harden_auth_schema.sql` | Adds constraints/indexes and hardens the initial schema. |
| `exception/GlobalExceptionHandlerTest.java` | Verifies exception-to-status/body mappings. |
| `service/AuthServiceTokenTest.java` | Verifies token-related auth-service behavior. |
| `service/JwtServiceTest.java` | Verifies JWT generation, claim extraction, expiry, and rejection. |
| `service/OtpServiceTest.java` | Verifies OTP generation, expiry, attempts, hashing, and rate limits. |

## 4. Route Service

Base folder: `route-service`

### Project, entry point, configuration, and web layer

| File | Responsibility |
|---|---|
| `pom.xml` | Declares MVC, JPA, validation, MySQL/Flyway, Eureka, Security, Actuator, Lombok, and test dependencies. |
| `README.md` | Route-service setup and API notes. |
| `RouteServiceApplication.java` | Starts the route bounded context and discovery client. |
| `config/LocaleConfig.java` | Configures locale/message resolution. |
| `config/SecurityConfig.java` | Protects management endpoints and accepts trusted gateway/internal identities. |
| `controller/RouteController.java` | Exposes route CRUD, search, details, and fare-related operations. |
| `controller/ScheduleController.java` | Exposes schedule creation, lookup, update, and deletion. |
| `controller/StopController.java` | Exposes ordered stop management for routes. |

### DTOs, domain, repositories, and mapper

| File | Responsibility |
|---|---|
| `dto/request/RouteRequestDto.java` | Route create/update input. |
| `dto/request/RouteSearchRequestDto.java` | Origin, destination, date/day, and search criteria. |
| `dto/request/ScheduleRequestDto.java` | Departure/arrival, operating-day, route, and capacity-related schedule input. |
| `dto/request/StopRequestDto.java` | Stop name/location/sequence input. |
| `dto/response/ApiErrorResponse.java` | Standard route error payload. |
| `dto/response/ApiResponseDto.java` | Generic success envelope. |
| `dto/response/FareResponseDto.java` | Calculated fare and supporting route/stop information. |
| `dto/response/RouteResponseDto.java` | Route representation returned to clients. |
| `dto/response/RouteSearchResponseDto.java` | Search result combining route, schedule, and stop/fare facts. |
| `dto/response/ScheduleResponseDto.java` | Public schedule representation. |
| `dto/response/StopResponseDto.java` | Public ordered-stop representation. |
| `entity/Route.java` | Route aggregate and its identifying/business fields. |
| `entity/Schedule.java` | A route’s timed operating instance/pattern. |
| `entity/Stop.java` | Ordered route stop used for search and fare segments. |
| `enums/DayOfWeekEnum.java` | Type-safe operating-day values. |
| `repository/RouteRepository.java` | Route CRUD and search queries. |
| `repository/ScheduleRepository.java` | Schedule queries by route/day/time and duplicate checks. |
| `repository/StopRepository.java` | Ordered stop and sequence queries. |
| `mapper/RouteMapper.java` | Converts route-domain objects to/from API DTOs. |

### Services, security, utility, and exceptions

| File | Responsibility |
|---|---|
| `service/RouteService.java` | Route CRUD/search contract. |
| `service/impl/RouteServiceImpl.java` | Implements route validation, persistence, search orchestration, and mapping. |
| `service/ScheduleService.java` | Schedule-management contract. |
| `service/impl/ScheduleServiceImpl.java` | Implements schedule rules and persistence. |
| `service/StopService.java` | Stop-management contract. |
| `service/impl/StopServiceImpl.java` | Implements ordered-stop rules and persistence. |
| `service/FareCalculationService.java` | Fare-calculation contract. |
| `service/impl/FareCalculationServiceImpl.java` | Calculates fare for the requested route segment. |
| `util/ScheduleDays.java` | Converts and evaluates configured operating days. |
| `security/HeaderAuthenticationFilter.java` | Builds authentication from gateway-provided user headers after trust validation. |
| `security/InternalValidationFilter.java` | Validates gateway/internal keys and rejects direct spoofed requests. |
| `exception/GlobalExceptionHandler.java` | Maps route-domain and validation failures to API errors. |
| `exception/DuplicateRouteException.java` | Signals a duplicate route definition. |
| `exception/DuplicateScheduleException.java` | Signals a conflicting/duplicate schedule. |
| `exception/DuplicateStopSequenceException.java` | Signals two stops attempting the same route sequence. |
| `exception/InvalidRouteSearchException.java` | Signals invalid origin/destination/date/search criteria. |
| `exception/RouteNotFoundException.java` | Signals a missing route. |
| `exception/ScheduleNotFoundException.java` | Signals a missing schedule. |
| `exception/StopNotFoundException.java` | Signals a missing stop. |

### Resources and tests

| File | Responsibility |
|---|---|
| `application.yml` | Imports `.env`; configures `9092`, database/Flyway/JPA, Eureka, security keys, localization, logging, and Actuator. |
| `messages.properties` | Default route-domain messages. |
| `messages_hi.properties` | Hindi route-domain messages. |
| `db/migration/V1__create_route_schema.sql` | Creates route, stop, and schedule tables and constraints. |
| `db/migration/V2__add_route_query_indexes.sql` | Adds indexes used by route/search queries. |
| `repository/RouteRepositoryIntegrationTest.java` | Tests real repository mappings and route queries. |
| `security/HeaderAuthenticationFilterTest.java` | Tests trusted user-header authentication. |
| `security/InternalValidationFilterTest.java` | Tests key validation and spoofing rejection. |
| `service/FareCalculationServiceImplTest.java` | Tests fare rules and invalid segments. |
| `service/RouteServiceImplTest.java` | Tests route CRUD/search business logic. |
| `service/ScheduleServiceImplTest.java` | Tests schedule rules and conflicts. |
| `service/StopServiceImplTest.java` | Tests stop ordering and duplicate sequences. |

## 5. Vehicle Service

Base folder: `vehicle-service`

### Project, application, configuration, clients, and controllers

| File | Responsibility |
|---|---|
| `pom.xml` | Declares MVC, JPA, validation, Feign, circuit breaker, Eureka, Security, MySQL/Flyway, Actuator, and tests. |
| `README.md` | Vehicle-service setup, endpoints, and integration notes. |
| `VehicleServiceApplication.java` | Starts the fleet/tracking service and enables discovery/Feign scanning. |
| `config/FeignConfig.java` | Propagates trusted identity/internal headers and configures outbound Feign behavior. |
| `config/SecurityConfig.java` | Secures fleet, tracking, and internal analytics endpoints. |
| `client/RouteServiceClient.java` | Calls Route Service to validate referenced routes and schedules. |
| `controller/VehicleController.java` | Exposes vehicle CRUD/status, assignment, availability, and tracking endpoints. |
| `controller/VehicleAnalyticsController.java` | Exposes service-key-protected internal vehicle/assignment analytics. |

### Request and response DTOs

| File | Responsibility |
|---|---|
| `dto/request/VehicleRequestDto.java` | Vehicle registration/update input and validation. |
| `dto/request/VehicleStatusRequestDto.java` | Controlled vehicle-status transition input. |
| `dto/request/AssignmentRequestDto.java` | Vehicle-to-route/schedule assignment input. |
| `dto/request/LocationRequestDto.java` | Latitude/longitude/time input for a location update. |
| `dto/response/ApiErrorResponse.java` | Standard vehicle error payload. |
| `dto/response/ApiResponseDto.java` | Generic successful response envelope. |
| `dto/response/VehicleResponseDto.java` | Safe public fleet representation. |
| `dto/response/AssignmentResponseDto.java` | Assignment state and referenced route/schedule facts. |
| `dto/response/LocationResponseDto.java` | Location point returned to callers. |
| `dto/response/RouteReferenceDto.java` | Minimal Route Service route contract used for validation. |
| `dto/response/ScheduleReferenceDto.java` | Minimal remote schedule contract used for validation. |
| `dto/response/VehicleAvailabilityResponseDto.java` | Availability/capacity view consumed by Ticket Service. |
| `dto/response/VehicleAnalyticsDto.java` | Vehicle status totals for Analytics Service. |
| `dto/response/CompletedAssignmentAnalyticsDto.java` | Completed-assignment facts used in performance reports. |

### Domain, persistence, projections, and mapper

| File | Responsibility |
|---|---|
| `entity/Vehicle.java` | Fleet asset with registration, capacity, status, and timestamps. |
| `entity/VehicleAssignment.java` | A vehicle’s assignment to a route/schedule and its lifecycle. |
| `entity/VehicleLocation.java` | Time-stamped GPS/location history for a vehicle. |
| `enums/VehicleStatus.java` | Allowed operational fleet states. |
| `enums/AssignmentStatus.java` | Allowed assignment lifecycle states. |
| `repository/VehicleRepository.java` | Vehicle lookup, uniqueness, status, and persistence queries. |
| `repository/VehicleAssignmentRepository.java` | Active/conflicting assignment and analytics queries. |
| `repository/VehicleLocationRepository.java` | Latest/history location queries. |
| `repository/projection/AssignmentStatusAnalyticsProjection.java` | Interface projection for counts grouped by assignment status. |
| `repository/projection/CompletedAssignmentAnalyticsProjection.java` | Projection of completed-assignment performance data. |
| `mapper/VehicleMapper.java` | Converts vehicle/assignment/location domain data to API DTOs. |

### Services, security, and exceptions

| File | Responsibility |
|---|---|
| `service/VehicleService.java` | Fleet CRUD, status, and availability contract. |
| `service/impl/VehicleServiceImpl.java` | Implements fleet rules, uniqueness, state changes, and repository calls. |
| `service/AssignmentService.java` | Vehicle assignment lifecycle contract. |
| `service/impl/AssignmentServiceImpl.java` | Prevents conflicts, validates remote references, and changes assignment state. |
| `service/TrackingService.java` | Submit/latest/history tracking contract. |
| `service/impl/TrackingServiceImpl.java` | Validates and persists GPS points and serves location queries. |
| `service/VehicleAnalyticsService.java` | Internal analytics contract. |
| `service/impl/VehicleAnalyticsServiceImpl.java` | Aggregates fleet/assignment repository projections. |
| `service/impl/RouteReferenceValidator.java` | Uses the Feign client to ensure route/schedule IDs exist and correspond. |
| `security/HeaderAuthenticationFilter.java` | Creates Spring authentication from trusted gateway headers. |
| `security/InternalValidationFilter.java` | Validates the gateway/internal trust key before accepting propagated identity. |
| `security/AnalyticsServiceKeyValidator.java` | Protects analytics-only endpoints with the analytics service key. |
| `exception/GlobalExceptionHandler.java` | Maps fleet, validation, remote-call, and unexpected failures. |
| `exception/VehicleNotFoundException.java` | Signals a missing vehicle. |
| `exception/DuplicateVehicleException.java` | Signals duplicate registration/identifier data. |
| `exception/AssignmentNotFoundException.java` | Signals a missing assignment. |
| `exception/AssignmentConflictException.java` | Signals overlapping or invalid assignment state. |
| `exception/LocationNotFoundException.java` | Signals that no requested location record exists. |
| `exception/RouteReferenceException.java` | Signals an invalid route/schedule relationship. |
| `exception/RouteServiceUnavailableException.java` | Converts remote Route Service failure into a domain-level availability error. |

### Resources and tests

| File | Responsibility |
|---|---|
| `application.yml` | Imports `.env`; configures `9094`, database/Flyway/JPA, Eureka/Feign/circuit breaker, service keys, logs, and Actuator. |
| `db/migration/V1__create_vehicle_schema.sql` | Creates vehicle, assignment, and location tables with constraints/indexes. |
| `repository/VehicleRepositoryIntegrationTest.java` | Verifies entity mapping, uniqueness, and vehicle queries. |
| `security/HeaderAuthenticationFilterTest.java` | Verifies authenticated identity creation from trusted headers. |
| `security/InternalValidationFilterTest.java` | Verifies direct/spoofed requests are rejected. |
| `service/VehicleServiceImplTest.java` | Tests fleet CRUD and status rules. |
| `service/AssignmentServiceImplTest.java` | Tests assignment creation, conflicts, and transitions. |
| `service/RouteReferenceValidatorTest.java` | Tests remote route/schedule validation and failure translation. |
| `service/TrackingServiceImplTest.java` | Tests location validation and latest/history behavior. |
| `service/VehicleAnalyticsServiceImplTest.java` | Tests projection-to-analytics aggregation. |

## 6. Ticket Service

Base folder: `ticket-service`

### Project, application, configuration, clients, controllers, and filter

| File | Responsibility |
|---|---|
| `pom.xml` | Declares MVC, JPA, validation, Feign, Resilience4j, Eureka, Security, MySQL/Flyway, logging, and tests. |
| `README.md` | Ticket-service booking, configuration, and integration notes. |
| `TicketServiceApplication.java` | Starts booking service and discovers Feign clients. |
| `config/FeignConfig.java` | Adds outbound trusted headers and defines Feign behavior. |
| `config/LocaleConfig.java` | Configures message localization. |
| `config/SecurityConfig.java` | Secures passenger/staff/admin, payment-callback, and analytics endpoints. |
| `client/RouteServiceClient.java` | Retrieves fare/route information from Route Service. |
| `client/RouteServiceFallbackFactory.java` | Builds a fallback that preserves the remote cause and returns a controlled failure. |
| `client/VehicleServiceClient.java` | Retrieves vehicle availability/capacity information. |
| `client/VehicleServiceFallbackFactory.java` | Builds controlled Vehicle Service failure behavior. |
| `controller/TicketController.java` | Exposes booking, read/list, cancellation, and related ticket APIs. |
| `controller/TicketPaymentController.java` | Exposes service-key-protected payment-context and payment-state callbacks. |
| `controller/TicketAnalyticsController.java` | Exposes service-key-protected ticket aggregates. |
| `filter/RequestResponseLoggingFilter.java` | Adds request/response diagnostics while avoiding sensitive payload leakage. |

### Request and response DTOs

| File | Responsibility |
|---|---|
| `dto/request/TicketRequestDto.java` | Booking details, passenger count, route/schedule/vehicle, and idempotency-related input. |
| `dto/request/PaymentUpdateRequestDto.java` | Payment Service callback input for ticket payment state. |
| `dto/response/ApiErrorResponse.java` | Standard booking error payload. |
| `dto/response/ApiResponseDto.java` | Generic success envelope. |
| `dto/response/TicketResponseDto.java` | Public ticket/booking state returned to clients. |
| `dto/response/FareResponseDto.java` | Remote Route Service fare contract. |
| `dto/response/VehicleAvailabilityResponseDto.java` | Remote Vehicle Service availability/capacity contract. |
| `dto/response/TicketPaymentContextDto.java` | Trusted ticket, owner, amount, and state facts returned to Payment Service. |
| `dto/response/TicketAnalyticsDto.java` | Ticket status/revenue-related aggregate consumed by Analytics. |
| `dto/response/RouteUsageAnalyticsDto.java` | Per-route usage aggregate consumed by Analytics. |

### Domain, persistence, projections, mapper, and utilities

| File | Responsibility |
|---|---|
| `entity/Ticket.java` | Booking aggregate containing owner, journey references, passenger/seat facts, fare, payment state, idempotency data, expiry, and version. |
| `enums/TicketStatus.java` | Booking/payment lifecycle states and legal transitions. |
| `repository/TicketRepository.java` | Booking lookup, ownership, capacity, idempotency, expiry, and analytics queries. |
| `repository/projection/TicketStatusAnalyticsProjection.java` | Counts/value grouped by ticket status. |
| `repository/projection/RouteUsageAnalyticsProjection.java` | Route-level usage projection. |
| `mapper/TicketMapper.java` | Converts Ticket entities into stable response DTOs. |
| `util/BookingRequestHasher.java` | Creates a deterministic request hash to detect idempotency-key reuse with different payloads. |
| `util/TicketNumberGenerator.java` | Generates externally safe ticket numbers separate from database IDs. |

### Services, security, and exceptions

| File | Responsibility |
|---|---|
| `service/TicketService.java` | Booking, lookup, cancellation, payment callback, and ownership contract. |
| `service/impl/TicketServiceImpl.java` | Orchestrates remote validations, fare, capacity reservation, idempotency, expiry, authorization, and state transitions. |
| `service/TicketAnalyticsService.java` | Internal ticket analytics contract. |
| `service/impl/TicketAnalyticsServiceImpl.java` | Aggregates repository projections for Analytics Service. |
| `security/HeaderAuthenticationFilter.java` | Converts trusted gateway identity headers into Spring authentication. |
| `security/InternalValidationFilter.java` | Validates trusted gateway/internal requests. |
| `security/PaymentServiceKeyValidator.java` | Restricts payment-context and callback endpoints to Payment Service. |
| `security/AnalyticsServiceKeyValidator.java` | Restricts analytics endpoints to Analytics Service. |
| `exception/GlobalExceptionHandler.java` | Maps domain, security, validation, and dependency failures to consistent errors. |
| `exception/TicketNotFoundException.java` | Signals a missing/inaccessible ticket. |
| `exception/SeatUnavailableException.java` | Signals capacity exhaustion or a concurrent reservation conflict. |
| `exception/InvalidTicketStateException.java` | Signals an illegal booking/payment/cancellation transition. |
| `exception/IdempotencyConflictException.java` | Signals reuse of one idempotency key for a different request. |
| `exception/RouteServiceUnavailableException.java` | Domain error for unavailable/failed Route Service calls. |
| `exception/VehicleServiceUnavailableException.java` | Domain error for unavailable/failed Vehicle Service calls. |

### Resources, migrations, and tests

| File | Responsibility |
|---|---|
| `application.yml` | Imports `.env`; configures `9093`, database, Flyway/JPA, Eureka/Feign/circuit breakers, holds/idempotency, service keys, logs, and Actuator. |
| `logback-spring.xml` | Ticket-specific log format, levels, and appenders/profile behavior. |
| `messages.properties` | Default booking/error messages. |
| `messages_hi.properties` | Hindi booking/error messages. |
| `db/migration/V1__create_ticket_schema.sql` | Creates the initial ticket schema. |
| `db/migration/V2__add_ticket_query_indexes.sql` | Adds booking/ownership/journey indexes. |
| `db/migration/V3__redesign_ticket_booking.sql` | Adds redesigned booking, idempotency, hold, and concurrency fields/constraints. |
| `db/migration/V4__add_payment_lifecycle.sql` | Adds payment-coordination fields and lifecycle support. |
| `repository/TicketRepositoryIntegrationTest.java` | Tests mapping, locking/query, idempotency, and aggregate queries. |
| `security/PaymentServiceKeyValidatorTest.java` | Tests authorized and rejected payment-service requests. |
| `service/TicketServiceBookingTest.java` | Tests happy-path booking, idempotent replay, capacity, and dependency failures. |
| `service/TicketServiceAuthorizationTest.java` | Tests ownership and role-based access. |
| `service/TicketPaymentLifecycleTest.java` | Tests valid/invalid payment state callbacks and expiry interactions. |
| `service/TicketAnalyticsServiceImplTest.java` | Tests ticket projection aggregation. |

## 7. Payment Service

Base folder: `payment-service`

### Project, application, configuration, client, and controllers

| File | Responsibility |
|---|---|
| `pom.xml` | Declares MVC, JPA, validation, Feign, Resilience4j, Eureka, Security, MySQL/Flyway, Actuator, Lombok, and tests. |
| `README.md` | Payment setup, API, and integration notes. |
| `PaymentServiceApplication.java` | Starts Payment Service and discovers its Feign client. |
| `config/FeignConfig.java` | Adds the Payment Service identity/key to trusted Ticket Service calls. |
| `config/SecurityConfig.java` | Secures passenger/admin payment APIs and internal analytics routes. |
| `client/TicketServiceClient.java` | Retrieves authoritative ticket/payment context and reports payment outcomes back to Ticket Service. |
| `controller/PaymentController.java` | Exposes initiate/get/refund payment APIs and delegates all rules to the service layer. |
| `controller/PaymentAnalyticsController.java` | Exposes service-key-protected payment aggregates for Analytics Service. |

### DTOs, domain, repository, projections, mapper, and utility

| File | Responsibility |
|---|---|
| `dto/request/PaymentRequestDto.java` | Ticket ID, method, idempotency information, and payment initiation input. |
| `dto/request/TicketPaymentUpdateDto.java` | Callback body sent to Ticket Service after payment state changes. |
| `dto/response/ApiErrorResponse.java` | Standard payment error payload. |
| `dto/response/ApiResponseDto.java` | Generic success envelope. |
| `dto/response/PaymentResponseDto.java` | Safe payment state returned to the client. |
| `dto/response/TicketPaymentContextDto.java` | Authoritative ticket owner, amount, and state received from Ticket Service. |
| `dto/response/PaymentAnalyticsDto.java` | Payment totals/status values consumed by Analytics. |
| `dto/response/PaymentMethodAnalyticsDto.java` | Counts/value grouped by payment method. |
| `entity/Payment.java` | Payment aggregate with ticket/user, amount, method, processor reference, status, idempotency hash/key, refund, and timestamps. |
| `enums/PaymentMethod.java` | Supported payment method choices. |
| `enums/PaymentStatus.java` | Payment lifecycle states and transition vocabulary. |
| `repository/PaymentRepository.java` | Lookup by ID/ticket/user/idempotency and payment analytics queries. |
| `repository/projection/PaymentMethodAnalyticsProjection.java` | Interface projection for payment-method aggregates. |
| `repository/projection/PaymentStatusAnalyticsProjection.java` | Interface projection for payment-status aggregates. |
| `mapper/PaymentMapper.java` | Converts Payment entities to public response DTOs. |
| `util/PaymentRequestHasher.java` | Hashes canonical initiation input to detect conflicting idempotency replays. |

### Processor strategy, services, security, and exceptions

| File | Responsibility |
|---|---|
| `processor/PaymentProcessor.java` | Strategy interface separating provider-specific charge/refund logic from domain orchestration. |
| `processor/ProcessorResult.java` | Provider-neutral processor success/failure result and reference. |
| `processor/SimulatedPaymentProcessor.java` | Development provider implementation that simulates payment/refund outcomes. |
| `service/PaymentService.java` | Payment initiation, lookup, and refund contract. |
| `service/impl/PaymentServiceImpl.java` | Validates owner/amount/state, enforces idempotency, invokes processor, persists transitions, and coordinates Ticket Service. |
| `service/PaymentAnalyticsService.java` | Internal payment aggregate contract. |
| `service/impl/PaymentAnalyticsServiceImpl.java` | Builds payment analytics from repository projections. |
| `security/HeaderAuthenticationFilter.java` | Creates authentication from trusted gateway headers. |
| `security/InternalValidationFilter.java` | Validates gateway/internal trust before accepting propagated identity. |
| `security/AnalyticsServiceKeyValidator.java` | Protects payment analytics from non-Analytics callers. |
| `exception/GlobalExceptionHandler.java` | Maps payment, validation, authorization, and dependency failures to API errors. |
| `exception/PaymentNotFoundException.java` | Signals a missing/inaccessible payment. |
| `exception/PaymentConflictException.java` | Signals duplicate/conflicting idempotency or payment state. |
| `exception/PaymentNotAllowedException.java` | Signals an ownership, amount, state, or refund rule violation. |
| `exception/PaymentProcessingException.java` | Signals processor/provider failure. |
| `exception/TicketServiceUnavailableException.java` | Signals that authoritative ticket validation/callback could not complete. |

### Resources and tests

| File | Responsibility |
|---|---|
| `application.yml` | Imports `.env`; configures `9095`, datasource/JPA/Flyway, Eureka/Feign/circuit breaker, processor settings, service keys, logs, and Actuator. |
| `db/migration/V1__create_payment_schema.sql` | Creates payment table, lifecycle fields, unique idempotency constraints, and query indexes. |
| `repository/PaymentRepositoryIntegrationTest.java` | Tests mapping, idempotency lookup, and analytics queries against a database. |
| `security/SecurityFiltersTest.java` | Tests trusted gateway and analytics-key filtering. |
| `service/PaymentServiceImplTest.java` | Tests initiation, replay/conflict, ownership, provider result, callbacks, and refund behavior. |
| `service/PaymentAnalyticsServiceImplTest.java` | Tests aggregation of status/method projections. |

## 8. Analytics Service

Base folder: `analytics-service`

### Project, application, configuration, clients, and controller

| File | Responsibility |
|---|---|
| `pom.xml` | Declares MVC, JPA, Feign, Resilience4j, Eureka, Security, MySQL/Flyway, Actuator, Lombok, and tests. |
| `README.md` | Analytics endpoint, setup, and dependency notes. |
| `AnalyticsServiceApplication.java` | Starts reporting service and discovers all analytics Feign clients. |
| `config/FeignConfig.java` | Adds Analytics Service key/identity to downstream internal calls. |
| `config/SecurityConfig.java` | Restricts report APIs by role and protects inbound trust boundaries. |
| `client/TicketAnalyticsClient.java` | Fetches ticket status and route-usage aggregates. |
| `client/PaymentAnalyticsClient.java` | Fetches payment status/method/value aggregates. |
| `client/VehicleAnalyticsClient.java` | Fetches fleet and completed-assignment aggregates. |
| `client/RouteScheduleClient.java` | Fetches schedule/reference information needed to enrich reports. |
| `controller/AnalyticsController.java` | Exposes live analytics, report generation, and saved-report retrieval APIs. |

### DTOs, saved-report domain, mapper, and repository

| File | Responsibility |
|---|---|
| `dto/ApiErrorResponse.java` | Standard analytics error payload. |
| `dto/ApiResponseDto.java` | Generic success envelope. |
| `dto/TicketAnalyticsDto.java` | Ticket status/count/value data received from Ticket Service. |
| `dto/RouteUsageAnalyticsDto.java` | Route-level demand/usage data. |
| `dto/PaymentAnalyticsDto.java` | Overall payment status/value data. |
| `dto/PaymentMethodAnalyticsDto.java` | Method-level payment aggregate. |
| `dto/VehicleAnalyticsDto.java` | Fleet/assignment status aggregate. |
| `dto/CompletedAssignmentAnalyticsDto.java` | Completed assignment details used for performance calculations. |
| `dto/ScheduleAnalyticsDto.java` | Route/schedule details used to enrich assignment facts. |
| `dto/PerformanceAnalyticsDto.java` | Calculated operational performance result. |
| `dto/ServiceReportResponseDto.java` | Saved report representation returned to clients. |
| `entity/ServiceReport.java` | Persisted point-in-time report snapshot and metadata. |
| `mapper/ServiceReportMapper.java` | Converts report entities to response DTOs and assists snapshot creation. |
| `repository/ServiceReportRepository.java` | Saves and queries generated reports. |

### Service, security, and exceptions

| File | Responsibility |
|---|---|
| `service/AnalyticsService.java` | Live aggregate, performance calculation, report generation, and retrieval contract. |
| `service/impl/AnalyticsServiceImpl.java` | Fans out to four services, combines responses, calculates metrics, handles missing sources, and persists reports. |
| `security/HeaderAuthenticationFilter.java` | Creates authentication from trusted gateway user headers. |
| `security/InternalValidationFilter.java` | Ensures propagated identity came through the trusted gateway path. |
| `exception/GlobalExceptionHandler.java` | Maps report and source failures to consistent API errors. |
| `exception/AnalyticsSourceUnavailableException.java` | Identifies which downstream analytics source failed or was unavailable. |
| `exception/ReportNotFoundException.java` | Signals a missing saved report. |

### Resources and tests

| File | Responsibility |
|---|---|
| `application.yml` | Imports `.env`; configures `9096`, database/Flyway/JPA, Eureka/Feign/circuit breakers, downstream service key, logs, and Actuator. |
| `db/migration/V1__create_analytics_schema.sql` | Creates the service-report snapshot schema and indexes. |
| `repository/ServiceReportRepositoryIntegrationTest.java` | Verifies report persistence and lookup queries. |
| `security/SecurityFiltersTest.java` | Verifies trusted gateway/internal filtering and role access. |
| `service/AnalyticsServiceImplTest.java` | Tests fan-out composition, metric calculations, report persistence, and dependency failures. |
| `exception/GlobalExceptionHandlerTest.java` | Tests analytics exception-to-response mappings. |

## About generated and wrapper files

Each service may also contain Maven Wrapper launchers/configuration (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/...`). They bootstrap a known Maven version; they contain no domain or Spring flow. `target/` contains compiled/generated output and can be recreated with Maven. `.idea/`, `.iml`, `.classpath`, and similar files are IDE metadata, not runtime configuration.

## Coverage rule

If a source file is added later, update this catalog and the relevant service chapter together. The catalog answers “what owns this file?”; the chapter answers “how does its behavior fit into the request and distributed flow?”
