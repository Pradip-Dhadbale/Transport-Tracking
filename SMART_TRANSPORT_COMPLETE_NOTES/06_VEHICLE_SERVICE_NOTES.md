# Vehicle Service — Complete Beginner-to-Interview Notes

## 1. Responsibility and boundary

Vehicle Service owns fleet operations:

- vehicle master data and operational status;
- date-specific vehicle-to-route/schedule assignments;
- availability/capacity lookup for Ticket Service;
- simulated GPS location history;
- assignment analytics for Analytics Service.

It owns three tables: `vehicles`, `vehicle_assignments`, and `vehicle_locations`. It stores Route Service IDs as scalar references because cross-service databases must remain independent.

## 2. How it builds on Route Service

```text
Dispatcher creates assignment
  -> Vehicle checks local conflicts
  -> Feign calls Route GET /api/routes/{routeId}
  -> Feign calls Route GET /api/routes/{routeId}/schedules/{scheduleId}
  -> validates active route/schedule + operating day
  -> stores routeId/scheduleId/serviceDate locally

Ticket -> Vehicle availability lookup
Analytics -> Vehicle assignment summary
```

Vehicle does not create a database foreign key to Route because the referenced rows live in another service/database. It validates through the owner service at write time.

## 3. File map

| Area | Files/responsibility |
|---|---|
| Entry | `VehicleServiceApplication` enables Boot and Feign clients. |
| Controllers | Main fleet/assignment/tracking API and internal analytics API. |
| Service contracts | Vehicle, Assignment, Tracking, VehicleAnalytics. |
| Implementations | CRUD/status, assignment state, GPS, analytics, route-reference validation. |
| Feign | `RouteServiceClient`, `FeignConfig`. |
| Entities | Vehicle, VehicleAssignment, VehicleLocation. |
| Repositories | One per entity plus two analytics projection interfaces. |
| DTOs | Four requests and eleven responses/reference/analytics wrappers. |
| Security | Gateway trust filter, identity filter, analytics key validator, method roles. |
| Mapper/errors | Static `VehicleMapper`, domain exceptions, global handler. |
| Resources/tests | YAML, V1 migration, repository/filter/service tests. |

## 4. `pom.xml` and startup

Dependencies follow the business-service pattern: Actuator, JPA, Security, Validation, MVC, Eureka, OpenFeign, MySQL runtime, Flyway, Lombok, test starters, H2.

`@SpringBootApplication` starts the service. `@EnableFeignClients` scans for `@FeignClient` interfaces and creates HTTP proxy beans. Injecting `RouteServiceClient` therefore injects a generated proxy, not a handwritten class.

Startup validates required gateway/analytics secrets (minimum 32 characters), configures Feign, migrates/validates `vehicle_db`, starts on 9094, and registers with Eureka.

## 5. `application.yml` — line-by-line

### Server/application/database

- `VEHICLE_SERVICE_PORT`, default 9094.
- `SERVICE_BIND_ADDRESS`, default loopback.
- current/parent optional `.env` imports.
- discovery name `vehicle-service`.
- MySQL `VEHICLE_DB_URL`, username, password, driver.
- Hibernate `ddl-auto=validate`, SQL logging disabled, MySQL dialect.
- Flyway enabled with baseline settings.

### OpenFeign

Under `spring.cloud.openfeign.client.config.default`:

- connect timeout default 3000 ms: maximum connection establishment wait;
- read timeout default 5000 ms: maximum response wait after connection;
- basic logging: method/URL/status/timing-level request data, depending on logger configuration.

Timeouts are mandatory in microservices; otherwise a Route outage can hold Vehicle requests indefinitely. This service does not currently configure a circuit breaker/retry for Route calls.

### Discovery/security/management

- Eureka configuration follows the shared pattern.
- `gateway.secret-key` validates gateway-origin identity and is also sent on Route Feign calls.
- `analytics.internal-secret` validates the Analytics caller.
- Actuator exposes only health/info without details.

No `bootstrap.yml` is present.

## 6. Entities and schema meaning

### `Vehicle`

- identity primary key;
- unique, normalized vehicle number;
- capacity;
- string enum status: AVAILABLE, IN_SERVICE, MAINTENANCE;
- active soft-delete flag;
- lifecycle callbacks set timestamps.

### `VehicleAssignment`

- belongs to local Vehicle through lazy many-to-one/FK;
- stores external `routeId` and `scheduleId` as scalars;
- `serviceDate` makes the assignment date-specific;
- ACTIVE, COMPLETED, CANCELLED state;
- assigned/completed timestamps.

### `VehicleLocation`

- belongs to Vehicle;
- exact decimal latitude/longitude/speed;
- caller-provided or server-generated recorded time.

There is no `@Version` optimistic-lock field in these entities. Concurrency is handled mainly by transaction isolation/checks. An optimistic version could protect competing updates to the same vehicle/assignment.

## 7. Controllers and role matrix

### Public-to-authenticated operational API

All business requests still require trusted gateway identity at the filter chain. “No `@PreAuthorize`” means any authenticated allowed role, not anonymous internet access.

| Endpoint | Authorized roles | Purpose |
|---|---|---|
| `GET /assignments/availability` | Authenticated/internal | Find active assignment and vehicle capacity for route/schedule/date. |
| `POST /vehicles` | Manager/Admin | Create vehicle. |
| `GET /vehicles`, `GET /{id}` | Authenticated | List/get active vehicles. |
| `PUT/PATCH /{id}` | Manager/Admin | Full number/capacity update. |
| `PATCH /{id}/status` | Dispatcher/Manager/Admin | Change operational status. |
| `DELETE /{id}` | Admin | Soft delete if no active assignment. |
| `POST /{id}/assignments` | Dispatcher/Manager/Admin | Create validated dated assignment. |
| `GET /{id}/assignments` | Dispatcher/Manager/Admin | History. |
| `PATCH .../complete` / `cancel` | Dispatcher/Manager/Admin | Finish active assignment. |
| `POST /{id}/locations` | Conductor/Dispatcher/Manager/Admin | Record location. |
| `GET /{id}/locations/latest` | Authenticated | Latest location. |
| `GET /{id}/locations?limit=` | Dispatcher/Manager/Admin | Bounded history. |

Like Route Service, PUT and PATCH share a fully required DTO, so the PATCH is effectively a full update.

### Internal analytics

`GET /api/vehicles/internal/analytics?from=&to=` requires:

1. trusted gateway key and identity filters;
2. Manager/Admin role through class-level `@PreAuthorize`;
3. correct `X-Analytics-Service-Key` via constant-time validator.

This layered check prevents a normal admin client from calling the internal endpoint unless it also possesses the analytics secret. Analytics Feign propagates admin identity and adds that secret.

## 8. DTO guide

### Requests

- `VehicleRequestDto`: number 2–50 and capacity 1–500.
- `VehicleStatusRequestDto`: required enum.
- `AssignmentRequestDto`: positive route/schedule IDs and future-or-present service date.
- `LocationRequestDto`: latitude −90..90, longitude −180..180, speed 0..250, controlled precision, optional timestamp.

### Responses

- `VehicleResponseDto`: fleet master/status/timestamps.
- `AssignmentResponseDto`: local assignment plus external references/date/state.
- `VehicleAvailabilityResponseDto`: assigned flag, identifiers, date, capacity; returns a normal “not assigned” payload rather than 404.
- `LocationResponseDto`: persisted coordinate sample.
- `RouteReferenceDto`/`ScheduleReferenceDto`: deliberately small projections of Route's response contract used by Feign.
- `VehicleAnalyticsDto`: counts plus completed operations.
- `CompletedAssignmentAnalyticsDto`: data Analytics needs to compare schedule and actual completion.
- standard success/error wrappers.

Spring/Jackson requirement: Feign response DTOs have no-args constructors and setters so JSON can be populated.

## 9. Repository layer

`VehicleRepository` handles active ordered list, active lookup, and case-insensitive duplicate checks.

`VehicleAssignmentRepository` supports:

- active vehicle/date conflict;
- active route/schedule/date conflict;
- availability lookup;
- any active assignment for maintenance/delete rules;
- vehicle history;
- assignment scoped by vehicle and ACTIVE state;
- grouped analytics and completed-operation projection.

`VehicleLocationRepository` finds newest sample and returns paged newest-first history.

Projection interfaces expose getter names matching JPQL aliases. Spring materializes only selected columns rather than full entities, useful for analytics.

## 10. Service layer

### `VehicleServiceImpl`

- Uppercases/trims vehicle number.
- Friendly duplicate checks plus DB unique constraint.
- Creates AVAILABLE and active.
- Lists only active vehicles.
- Maintenance is prohibited while an ACTIVE assignment exists.
- Delete is soft and prohibited with ACTIVE assignment.

The duplicate error says “active vehicle,” but the unique DB constraint covers inactive rows too. A soft-deleted vehicle number cannot be reused. That may be desirable for permanent fleet identity, but wording/rule should be explicit.

### `AssignmentServiceImpl`

Creation uses `@Transactional(isolation=SERIALIZABLE)`:

1. require active vehicle;
2. reject MAINTENANCE;
3. reject another active assignment for same vehicle/date;
4. reject another active vehicle for same route/schedule/date;
5. call Route Service to validate route/schedule/day;
6. save ACTIVE assignment.

Serializable isolation reduces racing conflict checks, but holds a database transaction while making two network calls. A better production design may validate remotely first, then acquire a short local lock/constraint-based reservation and recheck.

Finish operations require assignment ID, vehicle ID, and ACTIVE state, then set COMPLETED/CANCELLED and completion time. Repeating them returns not found rather than silently succeeding; decide whether idempotent completion is preferable.

Availability returns capacity but does not manage remaining seats. Ticket Service must address aggregate bookings/concurrency; raw vehicle capacity alone cannot prevent overbooking.

### `RouteReferenceValidator`

- Feign gets route and nested schedule.
- Validates successful response/data/IDs/active flags.
- Verifies schedule operates on service date.
- translates 404 to bad route reference;
- translates other Feign failures to 503.

There is duplicated schedule-day parsing from Route Service. Shared contract tests or a common stable format reduce drift without sharing business entities.

### `TrackingServiceImpl`

- only IN_SERVICE vehicles accept locations;
- missing timestamp becomes now;
- timestamp may be at most five minutes in future;
- latest throws 404 if none;
- history limit is 1–200 and uses `PageRequest`.

For high-frequency real GPS, a relational write per point may become expensive; time-series/stream storage and retention/downsampling are common evolutions.

### `VehicleAnalyticsServiceImpl`

- validates ordered date range up to 366 days;
- turns grouped projections into status-count map;
- safely fills missing status as zero;
- retrieves completed operations for punctuality calculation in Analytics.

## 11. Feign and identity propagation

`@FeignClient(name="route-service")` uses Eureka rather than a hard-coded URL.

`FeignConfig` interceptor:

1. reads current `SecurityContext`;
2. requires authenticated identity and a `ROLE_` authority;
3. adds user ID/role;
4. adds gateway shared secret.

This makes Route treat the internal call as acting on behalf of the original authenticated user. A background job without SecurityContext cannot validate routes through this client. For system jobs, introduce an explicit service identity rather than fabricating a user.

`@Configuration` on a Feign-specific config can also be discovered globally by component scanning, depending on package structure. Keep client-only config outside the main scan or carefully scope it if different clients need different interceptors.

## 12. Security flow

```text
request
 -> InternalValidationFilter verifies X-Gateway-Key
 -> HeaderAuthenticationFilter validates positive ID + known role
 -> SecurityFilterChain requires Authentication
 -> @PreAuthorize checks method role
 -> analytics controller additionally verifies analytics key
```

Health/info bypass internal secret. Comparisons use `MessageDigest.isEqual`.

Internal services currently reuse the gateway shared secret so Route/Vehicle accept their propagated calls. In production, mTLS or per-service credentials provide stronger attribution than one shared key.

## 13. Mapper and exceptions

`VehicleMapper` is a non-instantiable static utility mapping each entity to its DTO. Assignment/location mapping accesses the lazy Vehicle relationship within service transactions.

HTTP mapping:

- not found (vehicle/assignment/location) → 404;
- duplicate/assignment conflict/data-integrity → 409;
- invalid route reference/input → 400;
- Route unavailable → 503;
- analytics access denied → 403;
- validation → 400 field map;
- unexpected → safe 500.

Unexpected exceptions are not logged in the handler; add server-side logging/correlation without exposing details.

## 14. Migration line-by-line

### `vehicles`

- unique vehicle number;
- positive-capacity check;
- status/active fields;
- timestamps;
- `(active,status)` index.

### `vehicle_assignments`

- local Vehicle FK;
- external Route/Schedule scalar IDs;
- date/status/timestamps;
- indexes matching vehicle conflict/history and schedule availability checks.

There is no DB uniqueness enforcing only one ACTIVE assignment per slot. MySQL partial unique-index design is nontrivial because completed history must coexist; options include a separate active-slot table, generated column, or explicit locking.

### `vehicle_locations`

- coordinate/speed decimals;
- vehicle FK;
- `(vehicle_id, recorded_at)` index for latest/history.

## 15. Connected flows

### Fleet preparation

```text
Manager creates vehicle -> AVAILABLE
Dispatcher assigns route/schedule/date
 -> conflict checks
 -> Route validation over Feign
 -> ACTIVE assignment
Dispatcher sets vehicle IN_SERVICE
Conductor records locations
Dispatcher completes assignment
```

Status and assignment state are separate and not automatically synchronized. Operational procedures or service rules must decide when AVAILABLE ↔ IN_SERVICE occurs.

### Ticket availability

```text
Ticket Feign call with route/schedule/date
 -> find ACTIVE assignment
 -> require vehicle active
 -> return assigned + capacity
```

## 16. Test map

- repository integration: fleet, assignments, newest location ordering;
- header/internal filter validation;
- vehicle normalization, duplicate checks, maintenance rule, soft delete;
- assignment creation/conflicts/finish/availability/no-assignment;
- Route reference active/day/daily checks;
- tracking state/timestamp/limit/latest behavior;
- analytics status and completed-operation aggregation.

Missing/high-value:

- concurrent assignment race against real MySQL;
- Feign timeout/error contract integration;
- controller role matrix and analytics triple-auth checks;
- pagination/retention at GPS scale;
- consistency between vehicle status and assignments;
- booking capacity under concurrent tickets.

## 17. Best practices and pitfalls

- Add an explicit concurrency-safe active-slot mechanism.
- Avoid remote HTTP calls inside long serializable transactions.
- Introduce circuit breaker if Route outage volume can exhaust threads.
- Decide whether assignments automatically change Vehicle status.
- Add `@Version` or pessimistic locking for competing state transitions where appropriate.
- Define time zone for service dates/timestamps.
- Do not treat vehicle capacity as remaining seat count.
- Add pagination to fleet/assignment history.
- Plan GPS retention and avoid exposing sensitive live locations broadly.
- Use per-service authentication/mTLS instead of one global shared key.
- Keep historical vehicle number/capacity snapshots if audit requirements demand booking-time facts.

## 18. Interviewer favorite questions and answers

### Q1. Why no foreign key from assignment to Route?

Route data belongs to another database/service. Cross-database foreign keys couple deployment and availability. Vehicle stores IDs and validates through Route's API.

### Q2. What consistency guarantee does that provide?

Validation proves the reference existed/was active at assignment time. Route can later change, so ongoing consistency is eventual and requires lifecycle rules, events, revalidation, or snapshots.

### Q3. Why use Feign?

Feign turns an annotated Java interface into an HTTP client, integrates with discovery/load balancing, and reduces manual request code. It does not remove network failure; timeouts/error handling remain necessary.

### Q4. Does SERIALIZABLE fully solve duplicate assignments?

It can serialize conflicting indexed reads/writes in one database, but behavior depends on query/index/DB locking and it is expensive. A database-enforced reservation/unique model plus short transactions is more explicit.

### Q5. Optimistic versus pessimistic locking?

Optimistic locking uses a version and rejects stale updates at commit; best when conflicts are rare. Pessimistic locking locks rows during a transaction; stronger but reduces concurrency and risks deadlocks.

### Q6. Why projections for analytics?

They fetch only required grouped/scalar fields, reduce entity materialization, and make aggregate queries efficient.

### Q7. Why decimal coordinates?

Fixed precision avoids floating representation surprises and maps cleanly to database precision. Specialized spatial types/indexes are better for proximity queries.

### Q8. How would you scale tracking?

Ingest through a stream/broker, partition by vehicle/time, use time-series/spatial storage, retain/downsample old samples, cache latest location, and decouple ingestion from user queries.

### Q9. Why return assigned=false instead of 404?

“No active assignment for this slot” is an expected availability result, not necessarily an exceptional missing resource. It simplifies Ticket's decision path.

### Q10. How should service-to-service authentication work?

Prefer workload identity/mTLS or signed short-lived service tokens, authorize each caller, propagate end-user context separately, and avoid one shared static secret across every service.

### Q11. Why validate route operating day in Vehicle?

An assignment must be meaningful on its date. Vehicle retrieves authoritative schedule data and enforces that invariant before storing its local reference.

### Q12. What is a circuit breaker?

It stops repeatedly calling a failing dependency after a threshold, fails fast while open, then probes recovery in half-open state. It protects threads/latency but cannot invent valid route data.

## 19. One-minute revision

- Port 9094; fleet, assignments, GPS, assignment analytics
- Feign validates Route/Schedule/date
- Ticket reads assignment capacity
- Analytics reads counts/completed operations
- Three states each for vehicle and assignment
- Soft vehicle delete; assignments keep history
- GPS only for IN_SERVICE
- Trusted gateway headers + method roles + analytics secret
- Concurrency needs stronger explicit reservation design at scale
