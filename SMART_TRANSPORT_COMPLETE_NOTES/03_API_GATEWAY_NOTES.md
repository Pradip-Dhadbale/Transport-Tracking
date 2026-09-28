# API Gateway — Complete Beginner-to-Interview Notes

## 1. Why this service exists

API Gateway is the single intended HTTP entry point for the frontend. It listens on port 9090, matches request paths, discovers a target service through Eureka, validates access JWTs for protected routes, removes forged internal headers, adds trusted identity headers, applies CORS rules, logs routing information, and translates gateway-level failures into safe JSON errors.

It is built with **Spring Cloud Gateway WebFlux**, so its request pipeline is reactive and non-blocking. The six business services use servlet/MVC and embedded Tomcat. This distinction explains why Gateway uses `SecurityWebFilterChain`, `ServerWebExchange`, `Mono`, and `CorsWebFilter` rather than servlet APIs.

## 2. Position and request flow

```text
React client
  -> CORS/preflight handling
  -> route predicate selects target by path
  -> JwtAuthFilter (except Auth route)
       - validate HS256 signature and expiry
       - require token type ACCESS
       - validate subject and role
       - strip spoofed trusted/internal headers
       - add X-User-Id, X-User-Role, X-Gateway-Key
  -> Eureka + client-side load balancer resolves instance
  -> downstream microservice security filters
  -> controller
```

## 3. `pom.xml`

### Key coordinates

- Artifact: `api-gateway`
- Java target: 17
- Boot parent: 3.5.3
- Spring Cloud BOM: 2025.0.0

This service currently differs from most other services, which use a newer Boot/Cloud line. A gateway can run independently, but version families should be intentionally tested and documented. Do not casually align versions without checking Spring Cloud compatibility.

### Dependencies

- `spring-cloud-starter-gateway-server-webflux`: reactive Gateway engine, route predicates, filters, Reactor Netty integration, and Gateway auto-configuration.
- `spring-boot-devtools`: restart/development convenience.
- `spring-boot-starter-actuator`: health/info endpoints.
- `spring-boot-starter-security`: reactive security infrastructure.
- `spring-cloud-starter-netflix-eureka-client`: registry integration.
- `jjwt-api`: JWT types and builder/parser API.
- `jjwt-impl`: runtime implementation.
- `jjwt-jackson`: JSON serialization/deserialization for JWT claims.
- `lombok` with `provided` scope: compile-time boilerplate generation; not needed as a runtime application library.
- `spring-boot-configuration-processor`: metadata for typed configuration properties. The current gateway mainly uses `@Value`, so its benefit is limited unless configuration classes are introduced.
- `spring-boot-starter-test`: standard testing utilities.

Spring concept — reactive versus servlet stack:

- WebFlux represents asynchronous completion with `Mono`/`Flux`.
- Code must avoid blocking database/file/network calls on event-loop threads.
- Gateway filters return `Mono<Void>` and continue the chain with `chain.filter(exchange)`.

## 4. File-by-file explanation

### `ApiGatewayApplication.java`

- `@SpringBootApplication` enables auto-configuration and scans `com.transport.apigateway.*`.
- `SpringApplication.run` starts the reactive web application because the Gateway WebFlux starter is on the classpath.
- No explicit discovery annotation is present; Eureka client auto-configuration comes from the dependency.

### `CorsConfig.java`

- `@Configuration` makes the class a bean-definition source.
- `@Bean` registers one `CorsWebFilter`.
- `CorsConfiguration` allows four hard-coded Vite origins on localhost/loopback ports 5173/5174.
- Every request header and method is allowed by this Java bean.
- Credentials are allowed, so wildcard origin `*` cannot be used as a literal allowed origin.
- `UrlBasedCorsConfigurationSource` applies the policy to `/**`.

Important project observation: CORS is configured both here and in `application.yml`. The YAML version is more restrictive and environment-configurable, while this bean allows all headers/methods. Maintaining two policies can cause ambiguity or duplicate CORS headers. Prefer one authoritative configuration source.

### `SecurityConfig.java`

- `@EnableWebFluxSecurity` enables reactive Spring Security.
- `SecurityWebFilterChain` is the reactive counterpart of MVC's `SecurityFilterChain`.
- CSRF, HTTP Basic, and form login are disabled because this is a stateless token API.
- CORS support is enabled.
- Actuator health/info are permitted.
- `/api/**` is permitted at the generic Spring Security layer.
- Other exchanges are denied.

“Permit all” does not mean business routes are unauthenticated. Route-specific `JwtAuthFilter` instances enforce JWT authentication. This split works, but newcomers must understand that authentication is implemented as a Gateway filter rather than a Spring Security authentication mechanism.

### `JwtAuthFilter.java`

This is the security heart of the Gateway.

#### Class and constructor

- `@Component` registers the filter factory under the YAML name `JwtAuthFilter`.
- It extends `AbstractGatewayFilterFactory<Config>`, allowing YAML route filters to instantiate it.
- `@Slf4j` supplies a logger.
- The constructor injects `ObjectMapper`, `jwt.secret`, and `gateway.secret-key`.
- Startup fails when the JWT key is under 32 bytes or the gateway secret is under 32 characters. Failing at startup is safer than running with weak/missing trust material.

#### Trusted headers

- `X-User-Id`: authenticated subject.
- `X-User-Role`: validated role claim.
- `X-Gateway-Key`: proof that the gateway produced the trusted identity headers.
- `X-Payment-Service-Key` and `X-Analytics-Service-Key`: internal service headers that external clients must never be allowed to inject.

#### Filter algorithm

1. Read the path for logging.
2. If the method is `OPTIONS`, strip all trusted/internal headers and continue. Browsers need preflight without a JWT.
3. Require exactly one `Authorization` header.
4. Require `Bearer <token>`.
5. Parse and verify the signed JWT using the shared HMAC key.
6. Read subject, `role`, and `type` claims.
7. Require `type=ACCESS`; refresh/registration/reset tokens cannot authenticate API calls.
8. Require a nonblank subject and one of the known roles.
9. Mutate the downstream request:
   - remove any client-supplied trusted/internal headers;
   - set trusted user ID, role, and gateway key.
10. Continue the route chain.

#### Error behavior

- Expired token → 401 with a login-again message.
- JWT parsing/signature/malformed errors → 401.
- Unexpected authentication failure → logged and returned as 401.
- `onError` serializes `ErrorResponseDto` directly into the reactive response buffer.

Security insight: removing client headers before setting trusted values prevents privilege escalation such as sending `X-User-Role: ADMIN`. Downstream services must also validate `X-Gateway-Key`; trusting identity headers without that proof would be unsafe if the services are network-accessible.

### `LoggingFilter.java`

- Implements `GlobalFilter`, so it runs for every gateway route.
- Implements `Ordered`; `-1` gives it high precedence relative to ordinary filters.
- Reads `GATEWAY_ROUTE_ATTR` to obtain the selected route.
- Logs HTTP method, path, and route ID.
- Continues asynchronously.

It does not currently log completion status, latency, or correlation ID. Those are useful production enhancements. Never log bearer tokens or secrets.

### `ErrorResponseDto.java`

- Holds HTTP status, safe message, and timestamp.
- Lombok `@Data` generates getters/setters plus equality/string methods.
- `@Builder` enables fluent construction.
- `@Builder.Default` ensures the timestamp initializer still applies when using the builder.
- No-args/all-args constructors help serializers and direct construction.

### `GlobalExceptionHandler.java`

MVC's `@RestControllerAdvice` is not used because this is a reactive Gateway.

- Extends `AbstractErrorWebExceptionHandler`, Spring Boot's functional reactive error base.
- `@Component` registers it.
- `@Order(-2)` gives it precedence over the default reactive error handler.
- Constructor supplies error attributes, application context, and message codecs.
- `getRoutingFunction` routes every unhandled error to `handleError`.
- It preserves the discovered HTTP status when possible.
- It maps messages to safe generic text rather than exposing exception internals.
- 5xx failures get error logs with stack traces; 4xx failures get warning logs.
- It returns JSON `ErrorResponseDto`.

### Tests

`JwtAuthFilterTest` proves:

- client-supplied trusted headers are replaced/removed;
- refresh tokens cannot call APIs;
- preflight requests do not need JWT and cannot smuggle internal headers;
- unknown roles are rejected.

It creates real signed test tokens and captures the mutated exchange, which is stronger than merely asserting a helper method.

`GatewayCorsIntegrationTest` starts a real random-port Gateway context and proves:

- ticket-booking preflight allows `Idempotency-Key`;
- the Vite loopback origin is accepted;
- OPTIONS succeeds without JWT.

Discovery is disabled in the test so it does not require Eureka.

## 5. `application.yml` — line-by-line

### Server and safe errors

| Path | Meaning |
|---|---|
| `server.port=${API_GATEWAY_PORT:9090}` | Public local entry port. |
| `server.address=${API_GATEWAY_BIND_ADDRESS:127.0.0.1}` | Loopback-only by default. |
| `server.error.include-stacktrace=never` | Do not leak stack traces to clients. |
| `server.error.include-message=never` | Do not automatically expose exception messages. |

### Actuator

- Exposes only `health` and `info` over HTTP.
- `show-details: never` avoids revealing component details publicly.

### JWT and `.env`

- `jwt.secret: ${JWT_SECRET}` is required; there is no fallback.
- The two optional imports search the working directory and its parent for `.env`, parsed as properties.
- Required secret values still fail startup even though the file import itself is optional.

### Application and security auto-configuration

- `spring.application.name=api-gateway` is its Eureka name.
- Excluding `ReactiveUserDetailsServiceAutoConfiguration` avoids Boot generating a default user/password because this project authenticates with its custom Gateway filter.

### Gateway discovery and CORS

- Discovery locator is disabled: Eureka does not automatically expose every registered service as a public route.
- Explicit routes are safer because only intended path families are exposed.
- `add-to-simple-url-handler-mapping` helps CORS handling when no route handler is selected, especially preflight.
- `allowedOriginPatterns` comes from an environment property with local-development defaults.
- Allowed methods cover the CRUD verbs plus OPTIONS/PATCH.
- Allowed headers include Authorization, JSON content, language, correlation ID, and idempotency key.
- `X-Correlation-Id` may be read by frontend code because it is exposed.
- Credentials are allowed.
- Browser preflight may be cached for 3600 seconds.

### Routes

| Route ID | External path | Target | JWT filter? |
|---|---|---|---|
| `auth-service` | `/api/auth/**` | `lb://AUTH-SERVICE` | No |
| `route-service` | `/api/routes/**` | `lb://route-service` | Yes |
| `ticket-service` | `/api/tickets/**` | `lb://ticket-service` | Yes |
| `vehicle-service` | `/api/vehicles/**` | `lb://vehicle-service` | Yes |
| `payment-service` | `/api/payments/**` | `lb://payment-service` | Yes |
| `analytics-service` | `/api/analytics/**` | `lb://analytics-service` | Yes |

The Auth route omits the filter because login, OTP, refresh, and registration must work before an access token exists. Auth Service must enforce its own endpoint-specific rules.

`lb://` means “resolve instances using Spring Cloud LoadBalancer and discovery,” not a literal protocol sent over the network.

### Eureka and logging

- `defaultZone` points to the registry.
- Hostname/prefer-IP determine registration metadata.
- Logging levels keep framework output controlled while leaving CORS debugging enabled.
- Console pattern includes time, thread, level, logger, and message.

### Gateway secret

- `gateway.secret-key=${GATEWAY_SHARED_SECRET}` is required.
- The same value must be known to the downstream filters.
- It is an application-level shared secret, not a replacement for TLS/mTLS in production.

## 6. Authentication versus authorization

Gateway verifies **authentication facts**:

- token is well-formed and signed;
- token is not expired;
- token type is access;
- subject and role are recognized.

Downstream services perform **authorization**:

- can a passenger read this specific ticket?
- can only Admin/Transport Manager create a vehicle?
- can only the Payment Service call payment callbacks?

Keeping resource-ownership logic in the owning service is essential because Gateway does not have that service's database/domain context.

## 7. Connected examples

### Passenger fetches own tickets

```text
GET /api/tickets/me
Authorization: Bearer <access JWT>
  -> Gateway route ticket-service
  -> JwtAuthFilter validates token
  -> sets X-User-Id=42, X-User-Role=PASSENGER, X-Gateway-Key=<secret>
  -> Ticket HeaderAuthenticationFilter verifies gateway key
  -> TicketController receives trusted user context
```

### Payment calls Ticket internally

This call does not pass through the public Gateway. Payment's Feign interceptor adds `X-Payment-Service-Key`; Ticket's internal payment filter validates it. Gateway strips that header from external requests so a browser cannot impersonate Payment Service.

## 8. Best practices and project-specific pitfalls

- Consolidate Java and YAML CORS configuration into one policy.
- Keep Gateway and downstream services unreachable from untrusted networks except through intended entry points.
- Rotate `JWT_SECRET` and `GATEWAY_SHARED_SECRET` through a secret manager in production.
- HMAC verification means Auth and Gateway share the same signing secret. An asymmetric design could let Auth hold the private key and Gateway hold only a public key.
- Route-level filter authentication is outside Spring Security's authentication context. A standard reactive authentication filter can improve integration with authorization and observability, though the current design is understandable.
- Add rate limiting to login/OTP and public endpoints.
- Add request-size limits and explicit timeout/circuit-breaker policies.
- Generate/propagate a correlation ID and log response status/latency.
- Do not enable automatic discovery routes unless every registered service should become public.
- Verify version-family compatibility deliberately because this POM differs from most services.

## 9. Common mistakes

- Applying servlet `OncePerRequestFilter` in a WebFlux Gateway.
- Blocking inside a Gateway filter.
- Trusting user-supplied `X-User-Role`.
- Validating token signature but not token type.
- Protecting OPTIONS with JWT, which breaks browser CORS preflight.
- Using `allowedOrigins: "*"` together with credentials.
- Returning exception messages or stack traces to callers.
- Treating Gateway authentication as sufficient resource authorization.

## 10. Interviewer favorite questions and model answers

### Q1. Why use an API Gateway?

It gives clients one entry point and centralizes routing, cross-origin handling, edge authentication, request metadata, and common controls. Domain authorization remains in the owning services.

### Q2. Why is this Gateway reactive?

Spring Cloud Gateway's WebFlux server handles many concurrent proxy requests with a non-blocking event-loop model. It avoids dedicating a blocking servlet thread to each in-flight downstream call.

### Q3. What does `lb://ticket-service` mean?

Spring Cloud resolves `ticket-service` via discovery, obtains available instances, selects one using client-side load balancing, and forwards the HTTP request.

### Q4. Why strip identity headers?

External clients can send arbitrary HTTP headers. If the gateway forwarded `X-User-Role: ADMIN`, downstream trust would be broken. The gateway removes untrusted values and writes authenticated ones.

### Q5. Why validate authorization again downstream?

Gateway knows token identity but not every domain rule or resource owner. A ticket service must decide whether user 42 owns ticket 100. Defense in depth also reduces damage from routing/configuration mistakes.

### Q6. Why permit `/api/**` in `SecurityWebFilterChain`?

The project enforces JWT in named route filters. The generic chain allows routing to occur. It works, but security ownership must be documented because `permitAll` can look misleading.

### Q7. What is CORS, and does it protect backend-to-backend calls?

CORS is a browser-enforced cross-origin policy. It does not authenticate calls and does not stop curl or another server. Internal calls need real authentication and network controls.

### Q8. Why is CSRF disabled?

The API uses bearer tokens rather than browser-managed session cookies. If credentials move into automatically attached cookies, the CSRF decision must be revisited.

### Q9. How would you add rate limiting?

Use a Gateway rate-limiter filter backed by a shared store such as Redis, choose stable keys such as authenticated subject/IP/client, apply stricter rules to OTP/login, and return 429 with observable metrics.

### Q10. How do you handle a downstream outage?

Use short connection/read timeouts, circuit breakers where appropriate, safe error translation, health/metrics, and retries only for idempotent operations. Return a clear 503/502/504 rather than exposing internals.

### Q11. Why not expose routes automatically from Eureka?

Discovery is an operational registry, not an API allowlist. Automatic exposure can publish internal endpoints. Explicit predicates make the public surface deliberate.

### Q12. Symmetric versus asymmetric JWT signing?

With HMAC, every verifier knows the signing secret and could mint tokens if compromised. With RSA/ECDSA, Auth signs with a private key and Gateway verifies with a public key, reducing signing-key exposure.

## 11. One-minute revision

- Port 9090, reactive WebFlux
- Explicit path routes, Eureka-backed `lb://` targets
- Auth route public; other six route families use `JwtAuthFilter`
- Validates signature, expiry, access-token type, subject, and role
- Removes forged identity/internal headers
- Adds trusted user ID/role/gateway key
- Downstream services still authorize resources
- CORS handles browser preflight
- Global reactive handler produces safe JSON
