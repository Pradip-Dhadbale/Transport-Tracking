# Route Service — Complete Beginner-to-Interview Notes

## 1. Responsibility and business boundary

Route Service owns transport reference data:

- routes;
- ordered stops along each route;
- operating schedules;
- route search from one stop to a later stop;
- segment distance and fare calculation.

It is the source of truth for route/schedule identifiers used by Vehicle, Ticket, and Analytics. Those services never join its tables; they call its HTTP API.

## 2. Connection to earlier and later services

- Registers with Eureka as `route-service` on port 9092.
- External requests enter through Gateway, which validates JWT and sends trusted headers.
- `InternalValidationFilter` requires `X-Gateway-Key`; `HeaderAuthenticationFilter` builds Spring Authentication from `X-User-Id`/`X-User-Role`.
- Vehicle calls Route to validate route/schedule references before assignments.
- Ticket calls Route to calculate authoritative fare.
- Analytics calls Route to obtain schedule times for performance comparisons.

```text
Admin -> Gateway -> Route CRUD
Passenger -> Gateway -> route search/fare

Vehicle --------> Route lookup
Ticket ----------> Route fare
Analytics -------> Route schedule
```

## 3. File map

| Area | Files |
|---|---|
| Entry | `RouteServiceApplication` |
| Controllers | `RouteController`, `StopController`, `ScheduleController` |
| Contracts | `RouteService`, `StopService`, `ScheduleService`, `FareCalculationService` |
| Implementations | corresponding four `impl` classes |
| Persistence | `Route`, `Stop`, `Schedule`; three repositories |
| DTOs | route/stop/schedule requests; normal/search/fare responses; API wrappers |
| Mapping/utilities | `RouteMapper`, `ScheduleDays`, `DayOfWeekEnum` |
| Security/config | `SecurityConfig`, `LocaleConfig`, two filters |
| Errors | not-found, duplicate, invalid search, global handler |
| Resources | YAML, V1/V2 Flyway SQL, English/Hindi bundles |
| Tests | repository, filters, fare, route, stop, and schedule behavior |

## 4. `pom.xml`

The service uses Boot 4.1.0, Java 17, and Spring Cloud 2025.1.2.

- Actuator: health/info.
- Devtools: local restart.
- JPA: entities/repositories/transactions.
- Security and method security.
- Validation.
- `spring-boot-starter-webmvc`: servlet REST stack.
- Eureka client.
- MySQL runtime driver.
- Flyway starter and MySQL module.
- Lombok and compiler annotation processing.
- OpenFeign is present, but Route Service has no Feign client and does not enable Feign; this dependency is currently unnecessary.
- Focused test starters plus H2.
- Direct `spring-security-core` is normally redundant because the security starter already brings it.

## 5. Startup class

`RouteServiceApplication` uses `@SpringBootApplication`. No explicit Eureka annotation is required with the client starter and auto-configuration. Startup loads configuration, migrates/validates `route_db`, creates repositories/services/security, starts Tomcat, and registers the service.

## 6. `application.yml` — line-by-line

### Server and imported properties

- `server.port=${ROUTE_SERVICE_PORT:9092}`: default port.
- `server.address=${SERVICE_BIND_ADDRESS:127.0.0.1}`: local-only default.
- two optional `.env[.properties]` imports: current and parent working directory.
- `spring.application.name=route-service`: discovery name.

### Database

- `ROUTE_DB_URL` or default MySQL `route_db` URL.
- username/password are required.
- explicit MySQL driver.
- `ddl-auto=validate`: mappings must match migrations.
- SQL display off by default.
- explicit MySQL dialect.
- Flyway enabled, baseline-on-migrate true, baseline version 0.

### Discovery/security/management

- Eureka `defaultZone`, hostname, and prefer-IP follow the shared pattern.
- `gateway.secret-key=${GATEWAY_SHARED_SECRET}` is required and must match Gateway/other trusted internal callers.
- Actuator exposes health/info; detailed health is hidden.

No `bootstrap.yml` is present or required by the current local configuration design.

## 7. Domain model

### Route

`Route` is the aggregate root:

- identity-generated `routeId`;
- name, start point, end point;
- `active` soft-delete flag;
- lazy one-to-many stops ordered by `sequenceOrder`;
- lazy one-to-many schedules;
- cascade all plus orphan removal;
- create/update timestamps set by `@PrePersist`/`@PreUpdate`;
- active-column index declared in mapping/migration.

`cascade=ALL` means persistence operations on Route propagate to child collections. `orphanRemoval=true` means removing a child from a managed collection can delete it. The service normally manipulates child repositories directly, so both aggregate and repository behavior should stay consistent.

### Stop

- belongs to one Route with lazy `@ManyToOne`;
- name, sequence, cumulative distance from route start;
- unique `(route_id, sequence_order)` at database level;
- distance uses `DECIMAL(8,2)`/`BigDecimal`.

The cumulative-distance model makes a segment distance easy:

```text
distance(source -> destination)
= destination.distanceFromStart - source.distanceFromStart
```

### Schedule

- belongs to one Route;
- departure and arrival `LocalTime`;
- normalized comma-separated operating-day string;
- `active` supports soft deletion.

`@JsonBackReference` helps prevent recursive Route ↔ child serialization, but controllers return DTOs anyway, which is the stronger isolation.

## 8. Controllers/endpoints

### RouteController — `/api/routes`

| Endpoint | Role | Behavior |
|---|---|---|
| `POST /api/routes` | Manager/Admin | Create active route. |
| `GET /api/routes` | Authenticated | List active routes without child detail. |
| `GET /api/routes/{id}` | Authenticated/internal | Full active route with stops/schedules. |
| `PUT` or `PATCH /{id}` | Manager/Admin | Replace all three route fields. |
| `DELETE /{id}` | Admin | Soft-deactivate route and schedules. |
| `GET /search?from=&to=&travelDate=` | Authenticated | Find routes serving ordered stops and optional operating date. |
| `GET /{id}/fare?...` | Authenticated/internal | Calculate segment fare using schedule or explicit departure time. |

The same validated full `RouteRequestDto` is used for PUT and PATCH, so PATCH is not currently a partial update. Either expose PUT only or create nullable patch DTO semantics.

### StopController — `/api/routes/{routeId}/stops`

- create/update/delete: Manager/Admin;
- list/get: any authenticated trusted request;
- nested URLs express that a stop is scoped to a route;
- every lookup verifies the parent route and stop relationship.

### ScheduleController — `/api/routes/{routeId}/schedules`

- create/update/delete: Manager/Admin;
- list/get: authenticated/internal;
- delete means deactivate, not physical deletion;
- create/update prevent duplicate active departures on overlapping operating days.

Controllers return `ApiResponseDto<T>` directly. Spring MVC serializes it and defaults successful status to 200; create endpoints do not currently return 201.

## 9. DTOs and validation

### Requests

- `RouteRequestDto`: nonblank name/start/end, max 100. Entity/migration allow route name/endpoints up to 200, so API is intentionally/mistakenly stricter.
- `StopRequestDto`: nonblank max-100 name, positive sequence, nonnegative distance.
- `ScheduleRequestDto`: required departure/arrival, nonblank max-30 days.
- `RouteSearchRequestDto`: from/to fields, but controller accepts query parameters directly, so this DTO is currently unused.

Validation handles field shape. Service logic handles cross-field/domain rules such as different endpoints, chronological stops, and arrival after departure.

### Responses

- `RouteResponseDto`: route plus child lists.
- `StopResponseDto`: stop identity/name/order/distance.
- `ScheduleResponseDto`: times, normalized days, active.
- `RouteSearchResponseDto`: selected segment, distance, off-peak estimated fare, available schedules.
- `FareResponseDto`: route/stop/schedule context, effective departure, distance, fare, peak flag.
- `ApiResponseDto`: success/message/data/timestamp.
- `ApiErrorResponse`: status/message/timestamp/path.

## 10. Repository layer

### RouteRepository

Derived queries find/check active routes and case-insensitive names.

The JPQL search query joins the `stops` collection twice:

```sql
source.stopName = :from
destination.stopName = :to
source.sequenceOrder < destination.sequenceOrder
```

This ensures the route serves both stops in travel order. `DISTINCT` removes duplicates if stop names appear more than once.

### StopRepository

- ordered stops by route;
- stop scoped to an active parent route.

### ScheduleRepository

- active/all schedules by route;
- active schedule scoped to route.

Spring concept — nested property query: `findByRoute_RouteIdAndActiveTrue` traverses the entity association and produces a join/predicate without handwritten SQL.

## 11. Service layer

### RouteServiceImpl

#### Create/update/delete

- trim strings;
- start and end must differ;
- route name unique ignoring case at service level;
- create sets active;
- update ignores the same existing name but rejects another duplicate;
- delete soft-deactivates route and all loaded schedules.

There is no database unique constraint on route name. Concurrent duplicate creates could pass `exists` checks. Add a normalized unique database constraint for strong enforcement.

#### Search

1. Trim/require different source and destination.
2. Repository preselects active routes with correct stop order.
3. In each route, choose the matching forward segment with smallest distance.
4. Keep active schedules; when date is supplied, filter using `ScheduleDays.runsOn`.
5. Exclude route if a requested date has no schedule.
6. Calculate off-peak estimate.
7. Sort shortest distance, then route name.

Because mapper/search accesses lazy collections, `@Transactional(readOnly=true)` keeps persistence context open during mapping.

#### Fare

1. Require active route.
2. Require source/destination belonging to it.
3. Require destination sequence after source.
4. Subtract cumulative distances and require positive result.
5. Accept either `scheduleId` or `departureTime`, never both.
6. If schedule supplied, use its departure.
7. Apply peak logic and return two-decimal fare.

### StopServiceImpl

- parent must be active;
- sequence must be unique;
- previous distance must be lower;
- next distance must be higher;
- update excludes itself during duplicate validation;
- delete physically removes one stop.

The database unique constraint is the final concurrency guard, though persistence exceptions should be translated to a clean 409.

### ScheduleServiceImpl

- arrival must be after departure; overnight journeys are therefore not supported;
- normalize operating days;
- reject same departure if any operating day overlaps;
- update ignores itself;
- deletion sets `active=false`.

### FareCalculationServiceImpl

```text
base fare      = 10.00
distance rate  = 2.00 × km
peak surcharge = × 1.15
rounding       = scale 2, HALF_UP
morning peak   = 08:00 through 10:00 inclusive
evening peak   = 17:00 through 19:00 inclusive
```

`BigDecimal` avoids binary floating-point errors for money.

## 12. `ScheduleDays`

- utility class cannot be instantiated;
- accepts `DAILY` or tokens MON–SUN separated by comma/whitespace;
- normalizes to stable comma-separated enum names;
- maps Java `DayOfWeek` to three-letter enum;
- computes overlap with `EnumSet.retainAll`;
- produces clear error for unknown values.

Persisting days as a string is simple but harder to query/index. A join table or bit mask may fit more complex scheduling.

## 13. Mapper

`RouteMapper` is stateless/static:

- maps entities to safe DTOs;
- sorts stops by sequence;
- filters inactive schedules and sorts departure time;
- provides a no-relations mapping for cheap list responses.

Mapping inside the service transaction avoids lazy-initialization failures. An entity must not be returned directly because it exposes persistence relationships and can trigger recursion/unplanned queries.

## 14. Security

### Filter order

```text
InternalValidationFilter
  -> verify constant-time X-Gateway-Key
HeaderAuthenticationFilter
  -> validate positive user ID and known role
  -> set ROLE_* Authentication
Method authorization
  -> @PreAuthorize
```

Health/info bypass the gateway secret. All other direct requests are rejected.

`MessageDigest.isEqual` avoids simple early-exit string comparison timing behavior. Network-level TLS/mTLS remains recommended; a shared header alone is not full zero-trust security.

The same gateway secret is also used by internal Feign callers. They must inject the header in their `FeignConfig` or Route rejects the request.

## 15. Error handling

- not found → 404;
- duplicate route/sequence/schedule → 409;
- access denied → 403;
- validation/illegal argument/invalid search → 400;
- unknown error → safe 500.

Project-specific concern: the generic exception handler does not log the underlying exception, which makes production diagnosis difficult. Log server-side details while returning safe client text.

`MessageSource` is configured but most exceptions contain literal English rather than message keys, so localization is only partially implemented.

## 16. Migrations

### V1

- `routes`: identity key, name/endpoints, active, timestamps, active index.
- `stops`: route FK, name, order, cumulative decimal distance, unique route/order.
- `schedules`: route FK, times, day string, active, route index.
- InnoDB foreign keys enforce that child rows reference a real route.

### V2

- `(route_id, stop_name, sequence_order)` supports stop-based search/order.
- `(route_id, active, departure_time)` supports active schedule listing/sorting.

No cascade is declared in SQL foreign keys. JPA cascades handle application deletes, but direct database deletion behaves differently. The application uses route soft deletion, avoiding this mismatch in normal flow.

## 17. Main end-to-end flows

### Admin prepares a route

```text
create route
 -> unique name/different endpoints
add stops in sequence
 -> unique order + monotonic distance
add schedules
 -> valid time + normalized days + no overlap duplicate
```

### Passenger searches and books later

```text
GET /api/routes/search?from=A&to=C&travelDate=...
 -> ordered-stop JPQL
 -> date-filter schedules
 -> segment distance + estimated fare

Ticket later calls /{routeId}/fare
 -> authoritative selected-stop distance
 -> schedule departure peak decision
 -> exact fare returned to Ticket
```

## 18. Test map

- Repository: intermediate stops only in forward direction.
- Fare: peak/off-peak math, inclusive boundaries, negative rejection.
- Header/internal filters: valid identity, unknown role, missing/correct secret, public health.
- Route: forward/reverse search, day filtering, selected schedule fare, endpoint trimming, soft route delete.
- Schedule: normalization, unknown day, overlapping duplicate, soft delete, update self-exclusion.
- Stop: inactive parent, neighbor distance bounds, update self-exclusion, scoped delete.

Additional valuable tests:

- concurrent duplicate route/stop/schedule writes;
- controller role matrix;
- lazy collection/query-count performance;
- real MySQL migration/schema validation;
- overnight schedule requirement decision;
- internal Feign contract tests.

## 19. Best practices and pitfalls

- Add DB uniqueness for normalized route name.
- Avoid treating PATCH as full PUT.
- Decide whether physical stop deletion is safe after tickets reference stop IDs; soft deletion/versioning may be safer.
- Historical tickets need immutable route/stop snapshots because reference data can change.
- Consider route versioning/effective dates for production timetables.
- Document peak rules/time zone and externalize fare policy rather than constants.
- Use pagination for route lists.
- Use entity graphs/fetch joins or projections to control lazy-query counts.
- Remove unused Feign/security dependencies.
- Align API field lengths with schema.
- Add explicit correlation logging and exception diagnostics.

## 20. Interviewer favorite questions and answers

### Q1. Why store distance from start instead of distance to next stop?

Cumulative distance lets any segment distance be one subtraction. Validation must ensure values increase with sequence.

### Q2. Why use both service validation and a unique constraint?

Service validation gives friendly errors. The database constraint is the concurrency-safe final guarantee when two transactions race.

### Q3. What is the N+1 problem here?

Loading routes and then lazily fetching stops/schedules per route can produce many queries. Use fetch joins, entity graphs, batch fetching, or DTO projections based on access pattern.

### Q4. Why `@Transactional(readOnly=true)`?

It defines a consistent read boundary, may optimize persistence behavior, and keeps lazy relationships accessible during mapping. It does not by itself guarantee zero writes at every database.

### Q5. Why `BigDecimal` for fares?

Decimal money requires exact base-10 behavior and explicit rounding; `double` has binary representation error.

### Q6. PUT versus PATCH?

PUT replaces the full resource representation and should be idempotent. PATCH applies partial changes. This project maps both to a full required DTO, so it behaves like PUT.

### Q7. How does the route search ensure direction?

It joins stops as source/destination and requires source sequence less than destination sequence, then defensively checks again in service logic.

### Q8. How should cross-service references be modeled?

Vehicle/Ticket store scalar route/schedule IDs and validate through Route's API. There are no cross-database foreign keys; consistency is enforced by service contracts and lifecycle design.

### Q9. Soft delete pros/cons?

It preserves referenced history and prevents immediate loss, but every query must filter active state and uniqueness/history rules become more complex.

### Q10. How would route changes affect existing tickets?

Tickets should preserve booking-time snapshots such as stops, fare, schedule/travel date. Reference IDs alone can show altered history. Versioned routes/timetables are another approach.

### Q11. How does Spring implement repository interfaces?

Spring Data creates runtime proxy beans, derives queries from names, and executes JPQL supplied by `@Query` through the JPA EntityManager.

### Q12. Why separate controller, service, repository, and mapper?

Controller handles HTTP, service owns domain/transactions, repository owns persistence access, mapper isolates API models. Separation improves testing and change safety.

## 21. One-minute revision

- Port 9092; owns routes/stops/schedules/fares
- Ordered stops + cumulative distances
- Active soft deletion for routes/schedules
- Search uses two stop joins and sequence direction
- Fare = base + per-km, optional peak surcharge
- Vehicle/Ticket/Analytics call it internally
- Gateway secret validates trusted origin; roles authorize writes
- Flyway owns schema, Hibernate validates
