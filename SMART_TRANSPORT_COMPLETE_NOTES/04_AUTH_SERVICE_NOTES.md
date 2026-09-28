# Auth Service — Complete Beginner-to-Interview Notes

## 1. Responsibility and boundary

Auth Service owns identity and account security:

- passenger registration;
- OTP generation/verification;
- password and OTP login;
- access/refresh/registration/reset JWTs;
- refresh-token rotation and logout;
- password reset/change;
- profile, email verification, and language preference;
- staff creation and user administration;
- roles used throughout the platform.

It owns the `users`, `otp_verifications`, and `refresh_tokens` tables. Other services store `userId` references but must not query Auth's database directly.

## 2. How it connects to previous services

- It registers as `auth-service` in Eureka.
- Gateway forwards `/api/auth/**` without its route-level JWT filter because registration/login must be public.
- Auth Service protects its own non-public endpoints with its servlet Spring Security filter chain.
- Auth signs access JWTs with `JWT_SECRET`; Gateway verifies them with the same value.
- The access token contains subject/user ID, mobile number, role, language, type, issue time, expiry, and a unique JWT ID.
- Gateway converts validated claims into trusted identity headers for Route, Vehicle, Ticket, Payment, and Analytics.

```text
Registration/login request
  -> Gateway public Auth route
  -> AuthController
  -> AuthService / OtpService / JwtService
  -> auth database
  -> access + refresh token

Later protected request
  -> Gateway verifies the access token
  -> Gateway sends trusted identity headers to business service
```

## 3. Package/file map

| Package/file | Responsibility |
|---|---|
| `AuthServiceApplication` | Boot entry point and component-scan root. |
| `controller/AuthController` | Defines all public, authenticated-user, and admin HTTP endpoints. |
| `service/AuthService` | Main account use cases and transaction boundaries. Despite its name, this is a concrete service class, not an interface. |
| `service/JwtService` | Creates and parses four JWT types. |
| `service/OtpService` | Generates, hashes, stores, rate-limits, verifies, and consumes OTPs. |
| `OtpSender`, `EmailSender` | Delivery abstractions. |
| `ConsoleOtpSender`, `ConsoleEmailSender` | Local-development delivery implementations. |
| `config/AppConfig` | Password encoder and currently unused `RestTemplate` bean. |
| `config/SecurityConfig` | Stateless servlet security and method security. |
| `JwtLocaleResolver`, `LocaleConfig` | English/Hindi locale and message bundle configuration. |
| `JwtAuthenticationFilter` | Turns an access JWT into Spring Security `Authentication`. |
| `RequestLoggingFilter` | Logs request/response metadata and duration. |
| `CustomAuthenticationEntryPoint` | JSON 401 response. |
| `CustomAccessDeniedHandler` | JSON 403 response. |
| `entity/*` | JPA models for users, OTPs, and refresh tokens. |
| `repository/*` | Spring Data persistence interfaces. |
| `dto/request/*` | Validated input contracts. |
| `dto/response/*` | Safe API output contracts. |
| `exception/*` | Domain failures and centralized HTTP mapping. |
| `MessageUtil` | Locale-aware message lookup helper. |
| `messages*.properties` | English/Hindi business messages. |
| Flyway V1/V2 | Creates and hardens the schema. |

## 4. `pom.xml`

- `spring-boot-starter-web`: servlet Spring MVC, JSON, validation integration, embedded Tomcat.
- `spring-boot-starter-data-jpa`: Hibernate, Spring Data repositories, transactions.
- `mysql-connector-j`: MySQL JDBC driver. It would be clearer with runtime scope because application code does not compile against driver classes.
- Boot Flyway starter plus `flyway-mysql`: versioned schema migration and MySQL support.
- validation starter: Jakarta validation annotations and validator.
- security starter: filter chain, password encoding, method security.
- actuator: health/info endpoints.
- Eureka client: service registration/discovery.
- JJWT API/implementation/Jackson: token creation and parsing.
- Lombok: generated constructors/builders/accessors/loggers.
- focused Boot 4 test starters for JPA, security, validation, and MVC.
- compiler plugin lists Lombok as an annotation processor for main and test compilation.

Spring concept — starter: a starter is a curated dependency group that activates auto-configuration. Adding JPA does not create business repositories automatically; it makes the infrastructure available, and interfaces extending `JpaRepository` become proxied beans during scanning.

## 5. Startup

`AuthServiceApplication` contains `@SpringBootApplication` and calls `SpringApplication.run`.

Startup sequence relevant to this service:

1. Load YAML, environment variables, and optional `.env` imports.
2. Create datasource.
3. Run Flyway migrations.
4. Build JPA entity manager and validate entity/schema mappings.
5. Discover repository interfaces and create proxies.
6. Create configuration, encoder, services, filters, controller, and exception handlers.
7. `JwtService` validates token expiry settings and key length.
8. `OtpService` validates OTP configuration.
9. Build the Security filter chain.
10. Start Tomcat on port 9091 and register in Eureka.

## 6. `application.yml` — line-by-line

### Server/config import

| Property | Meaning |
|---|---|
| `server.port=${AUTH_SERVICE_PORT:9091}` | Listen on 9091 unless overridden. |
| `server.address=${SERVICE_BIND_ADDRESS:127.0.0.1}` | Loopback-only local default. |
| `spring.config.import` | Try `.env` in working directory and parent, parsed as properties. |
| `spring.application.name=auth-service` | Eureka/discovery name. |

### Datasource/JPA/Flyway

| Property | Meaning |
|---|---|
| `spring.datasource.url` | Required/overridable MySQL URL; default may create `transport_auth_db`. |
| `username`, `password` | Required environment properties; no defaults. |
| `driver-class-name` | MySQL JDBC driver. Usually Boot can infer this from the URL. |
| `jpa.hibernate.ddl-auto=validate` | Hibernate checks tables/columns but does not create/update them. |
| `jpa.show-sql=false` | Avoid SQL console noise by default. |
| `hibernate.format_sql=true` | Format SQL if SQL logging is enabled. |
| `database-platform` | Explicit MySQL dialect; modern Hibernate can usually infer it. |
| `flyway.enabled=true` | Run migrations at startup. |
| `baseline-on-migrate=true` / `baseline-version=0` | Allow adopting an existing nonempty schema with baseline semantics. Use carefully so bad environments are not silently accepted. |

Flyway owns schema evolution; Hibernate validates. This is safer than `ddl-auto=update`, which can perform uncontrolled schema changes.

### JWT

| Property | Default | Meaning |
|---|---:|---|
| `jwt.secret` | `mySuperSecretKey123456789` | HMAC signing key. The default is only 24 bytes while `JwtService` requires at least 32, so missing `JWT_SECRET` intentionally/accidentally fails startup. Remove the weak fallback for clearer configuration. |
| `access-token-expiry` | 900000 ms | 15-minute access token. |
| `refresh-token-expiry` | 604800000 ms | 7-day refresh token. |

The `.env` key must be exactly `JWT_SECRET`. A misspelling such as `4JWT_SECRET` causes fallback resolution.

### OTP

- Expiry default: 5 minutes.
- Maximum invalid attempts: 3.
- Resend cooldown: 60 seconds.
- Constructor guards reject invalid nonpositive values.

### Eureka/logging/app

- `EUREKA_URL` points to registry; hostname/prefer-IP control instance advertisement.
- Root/Auth logging is INFO; Spring Security is DEBUG (helpful locally, noisy and potentially sensitive in production).
- `app.environment` labels runtime environment.
- `app.sms.mode=console` selects the current console sender behavior.
- `app.otp.expose-in-logs=true` permits OTPs in logs for development. It must be false outside local development.
- Actuator exposes only health/info without details.

No `bootstrap.yml` exists; there is no remote Config Server/bootstrap property source.

## 7. Controller and endpoint guide

`@RestController` means returned objects are serialized as JSON. `@RequestMapping("/api/auth")` supplies the common prefix. Lombok `@RequiredArgsConstructor` creates constructor injection for final fields.

### Registration

| Endpoint | Access | Behavior |
|---|---|---|
| `POST /register/send-otp` | Public | Reject existing mobile; generate/store/send registration OTP. |
| `POST /register/verify-otp` | Public | Verify/consume OTP; return 10-minute `REGISTRATION` token. |
| `POST /register/complete` | Registration token | Parse mobile from token; create passenger; return access/refresh tokens. |

Why a short registration token? It proves the OTP step succeeded without creating a user prematurely. The controller verifies `type=REGISTRATION`, so an access/refresh/reset token cannot complete registration.

### Login and password recovery

| Endpoint | Behavior |
|---|---|
| `POST /login/send-otp` | Require active existing mobile, then send LOGIN OTP. |
| `POST /login/verify-otp` | Verify OTP and issue token pair. |
| `POST /login/password` | Verify email + BCrypt password and issue token pair. |
| `POST /forgot-password/send-otp` | Send RESET_PASSWORD OTP for an active account. |
| `POST /forgot-password/verify-otp` | Return short reset token. |
| `POST /forgot-password/reset` | Require reset token type; change BCrypt hash and remove all refresh tokens. |

### Token/session-like actions

- `POST /refresh`: validate signed refresh token, hash it, find active stored hash, verify expiry/subject/user, delete old token, and issue a new pair. This is refresh-token rotation.
- `POST /logout`: delete every refresh token for the authenticated user.

Access JWTs remain valid until expiry because they are stateless and are not stored/revoked. Fifteen-minute expiry limits that window.

### Current-user functions

- `PATCH /preferences/language`
- `GET /me`
- `PATCH /profile`
- `POST /email/send-otp`
- `POST /email/verify-otp`
- `PATCH /change-password`

The controller reads `Authentication.getName()`, which the JWT filter set to user ID.

### Admin functions

- `GET /admin/users`: `@PreAuthorize("hasRole('ADMIN')")`.
- `POST /admin/create-staff`: Admin or Transport Manager; prevents PASSENGER/ADMIN creation through this endpoint.
- `PATCH /admin/users/{id}/role`: Admin only; revokes refresh tokens after role change.
- `PATCH /admin/users/{id}/status`: Admin or Manager; deactivation revokes refresh tokens.

`@PreAuthorize` is active because `@EnableMethodSecurity` is on `SecurityConfig`.

## 8. DTOs and validation

`@Valid @RequestBody` makes Spring deserialize JSON, run constraints, and throw `MethodArgumentNotValidException` before the controller method executes if invalid.

| DTO | Fields and rules |
|---|---|
| `SendOtpRequestDto` | Indian 10-digit mobile beginning 6–9. |
| `VerifyOtpRequestDto` | Valid mobile plus exactly six numeric OTP digits. |
| `CompleteRegistrationRequestDto` | Name 2–100, optional valid email, password with uppercase/lowercase/digit, language `en`/`hi`. |
| `LoginWithPasswordRequestDto` | Required valid email and password. |
| `RefreshTokenRequestDto` | Nonblank refresh token. Redundant Lombok annotations could be simplified to `@Data` alone. |
| `ResetPasswordRequestDto` | Mobile plus new password, but service trusts token subject and does not use DTO mobile. Remove the redundant field to avoid conflicting identity inputs. Its `min=5` and “at least 8” message disagree. |
| `ChangePasswordRequestDto` | Current password; new password minimum 8 plus complexity. |
| `CreateStaffRequestDto` | Mobile, name, optional email, minimum-6 password, required role. |
| `UpdateLanguageDto` | `en` or `hi`; it lacks `@NotNull`, so a missing value can pass pattern validation. |
| `UpdateProfileRequestDto` | Optional name and email for partial update. |
| `UpdateUserRoleRequestDto` | Required enum role. |
| `UpdateUserStatusRequestDto` | Required Boolean active flag. |
| `VerifyEmailOtpRequestDto` | Nonblank OTP but no six-digit pattern, unlike mobile OTP DTO. |

Response DTOs:

- `ApiResponseDto<T>` standardizes `success`, `message`, and `data` and provides static factories.
- `AuthResponseDto` returns access token, refresh token, type, and user summary.
- `UserResponseDto` deliberately excludes password hash and internal timestamps.
- `ErrorResponseDto` carries status, message, and timestamp.

Best practice: make password policy consistent across registration, reset, change, and staff creation.

## 9. Entities

### `User`

- `@Entity` maps a managed Hibernate object.
- `@Table(name="users")` selects the table.
- Identity-generated `userId` is the primary key.
- Mobile and email are unique.
- Password stores only BCrypt hash.
- `Role` uses `EnumType.STRING`, preserving readable values and avoiding ordinal breakage.
- Verification/active flags have builder defaults.
- Preferred language defaults to English.
- `@CreationTimestamp` and `@UpdateTimestamp` are generated by Hibernate.

### `OtpVerification`

- Stores a general `identifier`, supporting mobile or email.
- Stores BCrypt OTP hash, never the clear OTP.
- Purpose prevents a LOGIN OTP from being reused for password reset.
- `used`, attempts, expiry, and creation time enforce one-time/limited use.

### `RefreshToken`

- Stores user ID and **SHA-256 hash of the JWT**, not raw refresh token.
- Expiry is duplicated from token claims for database-side checks/cleanup.
- `revoked` exists, although current logout/rotation deletes records rather than setting it.
- No JPA relationship to `User`; a scalar ID keeps the entity simple but the database also lacks a foreign key.

Entity caution: Lombok `@Data` generates `toString`, equality, and mutable setters for JPA entities. Explicit getters/setters and identifier-aware equality are safer in complex entities.

## 10. Repositories

Spring Data parses method names and builds queries:

- `UserRepository`: find/existence checks by mobile/email.
- `OtpVerificationRepository`: latest record by identifier/purpose, optionally unused; delete prior records for a purpose.
- `RefreshTokenRepository`: find active hash and delete all tokens for a user.

Example interpretation:

```java
findTopByIdentifierAndPurposeAndUsedFalseOrderByCreatedAtDesc(...)
```

means “select the newest unused OTP matching identifier and purpose.” Spring creates the implementation at runtime; no concrete repository class is needed.

`@Repository` is optional on a `JpaRepository` interface discovered by Spring Data, but documents intent.

## 11. Business services

### `AuthService`

Constructor injection comes from `@RequiredArgsConstructor`. `@Service` creates the bean. `@Transactional` methods commit all managed writes together or roll back on an unchecked exception.

Important rules:

- registration rejects duplicate mobile/email;
- every public registrant becomes PASSENGER;
- password is BCrypt encoded;
- active status is checked before login/reset/refresh;
- refresh token hashes are stored and rotated;
- reset/change/role change/deactivation revoke stored refresh tokens;
- email change clears verified status;
- DTO mapping prevents entity/password leakage.

### `JwtService`

Token types:

| Type | Subject | Purpose | Lifetime |
|---|---|---|---|
| `ACCESS` | user ID | General API authentication | Configured, default 15 min |
| `REFRESH` | user ID | Rotate/obtain new pair | Configured, default 7 days |
| `REGISTRATION` | mobile | Complete verified registration | 10 min |
| `RESET_PASSWORD` | mobile | Reset after OTP proof | 10 min |

All are HS256-signed. Each token has unique `jti`, so two tokens generated close together are still different.

### `OtpService`

1. Check newest OTP creation time for resend cooldown.
2. Generate six digits using `SecureRandom`.
3. Delete earlier identifier/purpose records.
4. BCrypt-hash OTP and store expiry.
5. Send through email or mobile abstraction.
6. During verification, require newest unused record, check expiry, attempts, and BCrypt match.
7. Increment failed attempts; mark successful/expired records used.

`SecureRandom` is required for security tokens; ordinary `Random` is predictable.

### Sender abstractions

Interfaces make provider replacement/test doubles easy. A production `SmsOtpSender` or mail provider can implement the interface while business code remains unchanged. `@Primary` tells Spring to choose `ConsoleOtpSender` if multiple `OtpSender` beans exist.

Critical current bug: both console senders log the OTP in the `expose-in-logs=false` path before logging the redacted message. The secret is therefore still exposed. The false branch must never include `otp` in any log call.

## 12. Security configuration and filters

### Filter chain

- CSRF disabled for bearer-token stateless API.
- `SessionCreationPolicy.STATELESS`: SecurityContext is not kept in an HTTP session.
- public registration/login/password-reset/refresh and health/docs routes are permitted.
- all others require authentication.
- JWT filter runs before `UsernamePasswordAuthenticationFilter`.
- custom handlers produce JSON 401 and 403 responses.

### `JwtAuthenticationFilter`

1. Runs once per servlet request.
2. If no Bearer header, continues; SecurityConfig later decides whether route is public.
3. Parses claims.
4. Applies language claim as request locale.
5. Only authenticates `ACCESS` tokens.
6. Creates authority `ROLE_<role>`.
7. Stores `UsernamePasswordAuthenticationToken` in `SecurityContextHolder`.
8. Invalid tokens are logged and left unauthenticated; protected routes get 401.

The filter does not reload the user from the database, so a deactivated user's existing access token can work until expiry. That is a normal stateless-token trade-off; short expiry or a revocation/version strategy can tighten it.

### 401 versus 403

- 401 entry point: no valid authentication.
- 403 access-denied handler: authenticated but lacks role/permission.

### Localization

- JWT language claim sets `RESOLVED_LOCALE`.
- `JwtLocaleResolver` first checks that attribute, then falls back to Accept-Language behavior.
- `LocaleConfig` supports English/Hindi and loads `messages*.properties`.
- `MessageUtil` looks up a key using `LocaleContextHolder`.

## 13. Exceptions

- `ResourceNotFoundException` → 404.
- `DuplicateResourceException` → 409.
- `InvalidCredentialsException`, `InvalidOtpException`, `InvalidTokenException` → 401.
- `OtpAttemptExceededException` → 400.
- `OtpRateLimitException` → 429.
- validation → 400 with field map.
- unsupported method → 405.
- malformed/illegal input → 400.
- authorization failures → 403.
- fallback → safe 500.

`@RestControllerAdvice` applies exception handling across controllers and serializes responses. Specific handlers should appear conceptually before generic handling; Spring selects by exception type, not source order.

## 14. Flyway migrations

### V1

- Creates `users` with unique mobile/email, role enum, verification/active state, language, and audit timestamps.
- Creates `otp_verifications` with hashed code, purpose, used/attempt state, expiry, timestamp.
- Creates `refresh_tokens` with user ID, token/hash column, expiry, revoked flag, timestamp.
- Uses InnoDB and `utf8mb4`.

### V2 hardening

- Temporarily adds historical `EMAIL_VERIFY`, migrates it to `EMAIL_VERIFICATION`, then narrows enum values. This is a safe data-before-constraint pattern.
- Adds composite OTP lookup index matching repository filters/order.
- Adds unique refresh-token hash index.
- Adds user ID index for logout/revocation queries.

There is no foreign key from refresh tokens to users. A foreign key with cascade or explicit cleanup policy could prevent orphan records, balanced against service-local lifecycle needs.

## 15. Complete flows

### Registration

```text
send OTP
 -> reject existing mobile
 -> hash/store REGISTRATION OTP
 -> deliver
verify OTP
 -> check newest unused + expiry + attempts + BCrypt
 -> mark used
 -> issue short REGISTRATION JWT (subject=mobile)
complete
 -> verify token type
 -> validate uniqueness
 -> BCrypt password
 -> save PASSENGER
 -> issue ACCESS + REFRESH
 -> store refresh hash
```

### Refresh rotation

```text
raw refresh JWT
 -> verify signature/expiry/type
 -> SHA-256 hash
 -> find non-revoked stored hash
 -> compare DB expiry and subject/user ID
 -> verify active user
 -> delete old record
 -> generate new unique token pair
 -> store only new refresh hash
```

If an old refresh token is stolen and used after rotation, its hash no longer exists and it is rejected.

## 16. Test guide

- `JwtServiceTest`: immediate refresh token generation remains unique because of `jti`.
- `AuthServiceTokenTest`: refresh rotation queries/stores hashes, not raw token.
- `OtpServiceTest`: expired OTP consumption, final-attempt lock, resend cooldown, email OTP consumption.
- `GlobalExceptionHandlerTest`: unsupported method is 405 and invalid business input is 400 rather than accidental 500.

Missing/high-value tests:

- controller/security integration for every public/protected/admin route;
- registration/login end to end with database;
- concurrency around duplicate registration/OTP attempts;
- role/status changes and access-token behavior;
- no-OTP-leak logging test;
- real MySQL via Testcontainers because H2 can differ in enum/index semantics.

## 17. Best practices and improvement opportunities

Highest priority:

- Fix false-branch OTP logging leak.
- Remove weak JWT fallback; require `JWT_SECRET` explicitly.
- Use a real SMS/email provider outside local development.
- Standardize password constraints and messages.
- Add rate limiting beyond one-record resend cooldown (per identifier, IP/device, and time window).

Design improvements:

- Parse Bearer headers strictly instead of `replace("Bearer ", "")`.
- Consider asymmetric JWT signing so Gateway only has a public key.
- Add token/key rotation strategy.
- Consider a user token version/security stamp for immediate access-token invalidation.
- Remove unused `RestTemplate` unless needed.
- Use typed `@ConfigurationProperties` with validation rather than many `@Value` fields.
- Avoid logging full mobile numbers in controller/service logs.
- Add cleanup jobs for expired OTP/refresh records.
- Consider normalized role/status columns rather than MySQL ENUM if roles evolve frequently.

## 18. Interviewer favorite questions and model answers

### Q1. Why use both access and refresh tokens?

Access tokens are short-lived and stateless for frequent API calls. Refresh tokens live longer, are stored as hashes, can be revoked/rotated, and obtain a new access token without asking for credentials every 15 minutes.

### Q2. Why hash refresh tokens in the database?

If the database leaks, stored hashes cannot directly authenticate. On refresh, hash the presented token and compare. This is similar to not storing raw passwords, though JWT entropy makes fast SHA-256 appropriate for lookup.

### Q3. Why BCrypt for six-digit OTPs?

The OTP space is small, so a fast hash would be easy to brute-force after database theft. BCrypt adds cost. Online attempt limits remain essential.

### Q4. What is refresh-token rotation?

Each valid refresh consumes/deletes the old token and issues a new one. Reuse of the old token fails, reducing the window for stolen refresh credentials.

### Q5. Why include token type?

Signature validity alone does not establish purpose. Type prevents a refresh, registration, or reset token from being accepted as a normal API access token.

### Q6. Why is `@Transactional` important in registration/refresh?

Related database changes must commit as one unit. For refresh rotation, deleting the old token and storing the new token should not leave an inconsistent half-finished state after an exception.

### Q7. What does `@PreAuthorize("hasRole('ADMIN')")` check?

Method security checks for authority `ROLE_ADMIN`. The JWT filter creates authorities in that format and `@EnableMethodSecurity` activates the check.

### Q8. Difference between 401 and 403?

401 means valid authentication is absent. 403 means identity is known but permission is insufficient.

### Q9. How is logout possible with stateless JWT?

This service deletes stored refresh tokens, preventing new access tokens. Existing access tokens are not centrally stored and remain valid until their short expiry unless an additional revocation/version scheme is used.

### Q10. Why use DTOs instead of returning `User`?

DTOs prevent leaking password hashes/internal fields, separate API contract from persistence, provide input validation, and allow either model to evolve independently.

### Q11. How would you prevent OTP abuse?

Use secure generation, hashed storage, short expiry, one-time consumption, attempt limits, resend cooldown, per-IP/device/account rate limits, monitoring, provider throttling, and never log OTPs in production.

### Q12. What happens if role changes after an access token is issued?

The old token contains the old role until expiry. Refresh tokens are revoked on role change, but immediate enforcement requires short token life, user lookup, token version, or revocation list.

### Q13. Why Flyway plus `ddl-auto=validate`?

Flyway provides reviewed/versioned deterministic schema changes. Hibernate validates mappings against that schema without making uncontrolled production changes.

### Q14. Why can the Gateway verify Auth's tokens?

Both use the same HMAC secret. Auth signs and Gateway recomputes/verifies the signature. This also means compromise of either secret holder could permit token minting, motivating asymmetric signing in stricter designs.

## 19. One-minute revision

- Port 9091; owns users, OTPs, refresh-token hashes
- Four JWT types with purpose checks
- Access 15 min; refresh 7 days defaults
- BCrypt passwords and OTPs
- SHA-256 refresh-token lookup hashes
- Stateless Spring Security plus method roles
- English/Hindi locale support
- Registration and reset use short proof tokens
- Refresh rotates; logout/revocation deletes stored refresh tokens
- Fix current OTP logging leak and weak YAML fallback
