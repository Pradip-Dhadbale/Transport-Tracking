# Analytics Service — Complete Beginner-to-Interview Notes

## 1. Responsibility and reporting model

Analytics Service provides a read/composition layer:

- live ticket usage from Ticket Service;
- live revenue from Payment Service;
- live operational performance from Vehicle + Route;
- persisted service-report snapshots combining all metrics;
- report retrieval/history.

It does not query other services' databases. It calls their internal analytics APIs, which preserves data ownership.

## 2. Full dependency fan-out

```text
Manager/Admin -> Gateway -> Analytics

/usage -------> Ticket analytics
/revenue -----> Payment analytics
/performance -> Vehicle analytics
                    |
                    +-> unique Route schedule lookups

/reports
  -> usage + revenue + performance
  -> persist immutable-like ServiceReport snapshot
```

This is **API composition**: combine synchronous APIs at request time. It is simple/fresh but inherits source latency and availability.

## 3. File map

| Area | Files |
|---|---|
| Entry | `AnalyticsServiceApplication` |
| Controller | `AnalyticsController` |
| Contract/implementation | `AnalyticsService`, `AnalyticsServiceImpl` |
| Feign clients | Ticket, Payment, Vehicle, Route Schedule |
| Configuration/security | Feign interceptor, filter chain, gateway/identity filters |
| Persistence | `ServiceReport`, repository, mapper |
| DTOs | source contracts, performance/report/wrappers |
| Errors | source unavailable, report not found, global handler |
| Resources/tests | YAML, report migration, service/repository/security/error tests |

## 4. POM/startup

Dependencies: MVC, JPA, Security, Validation, Actuator, Eureka, OpenFeign, Resilience4j, MySQL/Flyway, Lombok, focused tests/H2.

`@EnableFeignClients` creates four discovery-aware proxies. At startup the service validates gateway/analytics secrets, migrates `analytics_db`, disables generated default-user auto-configuration, starts on 9096, and registers in Eureka.

## 5. `application.yml` — line-by-line

### Server/application/security auto-config

- port 9096 and loopback bind default;
- optional current/parent `.env` imports;
- discovery name `analytics-service`;
- excludes Boot's `UserDetailsServiceAutoConfiguration` because authentication comes from trusted headers, not generated basic credentials.

### Database/JPA/Flyway

- MySQL `analytics_db` default URL and required credentials;
- Hibernate validate/no SQL/MySQL dialect;
- `open-in-view=false`: closes persistence context at service transaction boundary rather than leaving it open through web rendering; DTO mapping occurs inside services;
- Flyway enabled/baseline settings.

### Feign/resilience

- circuit-breaker-enabled Feign;
- 3-second connect, 5-second read timeout, BASIC logging;
- default circuit: window 10, minimum 5, 50% threshold, 10-second open wait;
- default time limiter: 8 seconds, cancel future when timed out;
- Actuator exposes health/info/circuitbreakers.

Timeout applies per source call. A report performs several sequential calls, so total request latency can exceed one source timeout.

### Trust/reporting policy

- gateway secret validates entry and downstream calls;
- analytics secret proves Analytics to Ticket/Vehicle/Payment;
- on-time grace defaults to 15 minutes.

There is no startup validation preventing a negative grace value. Typed validated configuration would improve this.

No `bootstrap.yml` is present.

## 6. Controller

Class-level `@PreAuthorize("hasAnyRole('TRANSPORT_MANAGER','ADMIN')")` protects every endpoint.

| Endpoint | Result |
|---|---|
| `GET /api/analytics/usage?from=&to=` | Live Ticket usage. |
| `GET /revenue?from=&to=` | Live Payment revenue. |
| `GET /performance?from=&to=` | Live Vehicle/Route punctuality. |
| `POST /reports?from=&to=` | 201; generates and persists combined snapshot. |
| `GET /reports/{id}` | Saved snapshot. |
| `GET /reports` | Saved reports newest first. |

Date parameters use `@DateTimeFormat(ISO.DATE)` and must be `yyyy-MM-dd`.

Controller extracts positive numeric user ID and a Manager/Admin role from Authentication, then passes both explicitly to the service so Feign can propagate them.

## 7. Feign clients and trust propagation

### Source clients

- Ticket summary: `/api/tickets/internal/analytics`.
- Payment summary: `/api/payments/internal/analytics`.
- Vehicle summary: `/api/vehicles/internal/analytics`.
- Route schedule: `/api/routes/{routeId}/schedules/{scheduleId}`.

Each method explicitly sends end-user ID/role. The Feign interceptor adds:

- `X-Gateway-Key` so each downstream internal-validation filter accepts the call;
- `X-Analytics-Service-Key` so analytics-specific controllers accept it.

Route's ordinary schedule endpoint does not check analytics key specifically, but receives it harmlessly along with required gateway/user headers.

No fallback factories are defined. `AnalyticsServiceImpl` catches source runtime failures and translates them to source-specific 503.

## 8. DTO contracts

Source DTOs use no-args constructors/setters for Feign/Jackson:

- `TicketAnalyticsDto` and per-route usage;
- `PaymentAnalyticsDto` and per-method analytics;
- `VehicleAnalyticsDto` and completed-operation list;
- `ScheduleAnalyticsDto`.

Local built DTOs use builders:

- `PerformanceAnalyticsDto`;
- `ServiceReportResponseDto`;
- API/error wrappers.

Notice Payment method is a String here, not Payment Service's enum. This reduces compile-time coupling to another service's Java type, though accepted values still form a contract.

## 9. Live usage and revenue

`usage` and `revenue`:

1. validate from/to and maximum 366 days;
2. call source with identity/role;
3. require non-null success response and data;
4. return exact source DTO;
5. log source failure server-side and return a source-specific 503.

Analytics does not recompute ticket/payment aggregates, keeping the owning service responsible for status semantics.

## 10. Performance calculation

### Source data

Vehicle returns assignment counts and completed operations containing route ID, schedule ID, service date, and actual `completedAt`.

### Schedule cache

A request-local `Map<ScheduleKey, ScheduleAnalyticsDto>` ensures multiple completed assignments for the same route/schedule trigger only one Route call.

### On-time algorithm

```text
scheduledArrival = serviceDate + schedule.arrivalTime
if arrivalTime <= departureTime:
    scheduledArrival += 1 day      // overnight schedule

on time when:
actual completedAt <= scheduledArrival + grace minutes
```

Percentage:

- zero completed → 0.00%;
- otherwise `onTime × 100 / completed`, scale 2 HALF_UP.

The denominator uses Vehicle's completed count. Completed operations missing timestamps remain in the denominator but cannot count on time, which effectively treats incomplete analytics data as late.

Performance can create one Vehicle call plus one sequential Route call per unique schedule. This is an N+1 remote-call pattern. Batch schedule endpoint, caching, parallel bounded calls, or event-fed read models can reduce it.

## 11. Report generation

`generateReport` calls usage, revenue, and performance, then stores selected scalar metrics plus generator/period.

Why store a snapshot?

- later reads are fast;
- the numbers preserve what sources reported at generation time;
- historical reports do not change whenever live status changes.

Current `@Transactional` begins before three source fan-outs and remains open until save/commit. Since there is no DB work until the end, fetch live data before opening a short persistence transaction to avoid holding connection/transaction resources across network waits.

All sources are required: one outage fails report generation. That ensures completeness but provides no partial/degraded report. A production design can store source status/completeness flags and choose explicit partial-report semantics.

## 12. ServiceReport entity

Stores:

- period/generator/generation time;
- ticket/passenger/cancellation counts;
- payment attempts/outcomes and gross/refund/net;
- assignment/completion/cancellation/on-time metrics;
- optimistic `version`.

`@PrePersist` sets generation time. `DECIMAL(12,2)` stores monetary totals and `DECIMAL(5,2)` stores percentage up to 100.00.

The report omits:

- expired/payment-failed ticket counts;
- active assignments and grace minutes;
- route/payment-method breakdowns;
- source versions/timestamps/completeness.

Those remain in live DTOs but are not in snapshots. Decide report contract intentionally.

## 13. Repository and mapper

`ServiceReportRepository` extends `JpaRepository` and adds generated-time descending list. No pagination is present.

`ServiceReportMapper` copies every stored metric to response and maps lists. Mapping inside read-only transaction works with `open-in-view=false`.

## 14. Security flow

External request:

```text
Gateway JWT -> gateway key/user headers
 -> Analytics gateway-secret filter
 -> identity filter creates ROLE_MANAGER/ADMIN
 -> class @PreAuthorize
```

Downstream source call:

```text
Analytics Feign
 -> original manager/admin ID/role
 -> gateway shared secret
 -> analytics internal secret
 -> source validates all layers
```

The same static gateway secret is shared widely; use mTLS/workload identities and per-service authorization in a production trust model.

## 15. Exception behavior

- missing report/resource → 404;
- invalid date/range/role → 400;
- missing/type-mismatched query parameter → 400;
- source unavailable/invalid response → 503;
- access denied → 403;
- unexpected → logged safe 500.

Source errors deliberately do not expose Feign/internal exception details to clients.

## 16. Migration

V1 creates one `service_reports` table matching the entity:

- identity key;
- all non-null snapshot metrics;
- money/percentage precision;
- version;
- period and generated-time indexes.

`version` has no explicit default in entity but Flyway column is non-null without default. Hibernate supplies version 0 on insert; integration tests confirm persistence.

## 17. Freshness and consistency

A report is not an atomic snapshot across all services:

1. Ticket is read at time A.
2. Payment at time B.
3. Vehicle/Route at later times.

Changes between calls can produce a mixed-time view. A persisted report freezes the combined result but does not make source reads globally transactional.

Options for stronger analytical consistency:

- pass a cutoff timestamp supported by every source;
- use events/change-data capture into an analytics store;
- build periodic snapshots;
- record source-as-of timestamps and versions;
- accept/document eventual consistency.

## 18. Test map

- service: usage passthrough, on-time percentage, schedule-call caching, overnight arrival, combined report persistence, invalid/unavailable source;
- repository: persist/order reports;
- filters: missing gateway rejection and manager Authentication;
- handler: missing parameter 400, missing resource 404.

High-value additions:

- source timeout/circuit-open behavior;
- large unique-schedule fan-out/latency;
- partial-source design decision;
- concurrent report generation;
- source contract tests;
- report pagination;
- grace validation;
- consistency/cutoff semantics.

## 19. Best practices and evolution path

Near-term:

- validate grace ≥ 0 with typed `@ConfigurationProperties`;
- shorten report DB transaction;
- batch Route schedule retrieval;
- add pagination;
- add correlation/distributed tracing;
- persist source timestamps/completeness and report policy fields.

At scale:

- publish Ticket/Payment/Vehicle/Route domain events;
- consume into an analytical read model/warehouse;
- use materialized aggregates;
- make dashboards independent of source uptime;
- handle late/duplicate events idempotently;
- define event-time windows, time zones, and metric ownership;
- protect financial/operational analytics with audit logging and least privilege.

## 20. Interviewer favorite questions and answers

### Q1. What is API composition?

A service calls multiple owner APIs at request time and combines results. It is easy and fresh but latency/availability depend on all sources.

### Q2. Why not query every database directly?

That breaks service ownership, couples schemas/deployments, bypasses authorization/business semantics, and makes independent evolution difficult.

### Q3. What is fan-out latency?

One request produces multiple downstream calls. Sequential latency adds; parallel latency approaches the slowest call but increases concurrency/load. Timeouts and budgets are essential.

### Q4. Why cache schedule lookups inside one request?

Many operations share one route/schedule. Request-local caching prevents duplicate remote calls without serving stale data across requests.

### Q5. Is the saved report strongly consistent?

No. Each source is read at a different time. It is a durable snapshot of a composed eventually consistent view.

### Q6. When should analytics become event-driven?

When source fan-out is too slow/unreliable, history/scale grows, cross-source metrics need reproducibility, dashboards need independence, or complex aggregations require a warehouse/read model.

### Q7. How do circuit breaker and time limiter differ?

Time limiter stops one slow call after a deadline. Circuit breaker uses recent outcomes to fail fast across calls when a dependency appears unhealthy.

### Q8. What is a materialized view/read model?

Precomputed data shaped for queries, updated from source changes/events. Reads are fast but data is eventually consistent and requires update/rebuild logic.

### Q9. Why use half-open date-time intervals in Payment analytics?

`>= start` and `< nextDayStart` includes the full end date without depending on timestamp precision or “23:59:59.999...” assumptions.

### Q10. How is on-time performance calculated?

Compare completion to scheduled arrival plus grace; handle overnight arrival by adding one day; divide on-time completed operations by completed assignment count and round to two decimals.

### Q11. How should partial source failure be handled?

Choose explicitly: fail the request for complete-only reports, or return/store partial data with per-source status, timestamp, and completeness flag. Never silently present partial data as complete.

### Q12. How would you secure analytics?

Manager/Admin authorization, service identity, least privilege, private network/TLS, secret rotation, audit report access/generation, protect financial data, and avoid exposing raw sensitive user data.

### Q13. Why `open-in-view=false`?

It prevents hidden database access during web serialization and forces required data loading/mapping inside explicit service transaction boundaries.

### Q14. Why persist generatedBy?

It provides accountability/audit context. A fuller model would also store parameters, grace/policy version, source timestamps, and generation status.

## 21. One-minute revision

- Port 9096; Manager/Admin only
- Live usage from Ticket, revenue from Payment
- Performance from Vehicle + cached Route schedule calls
- 15-minute default on-time grace
- Report combines and persists snapshot
- Source failure → explicit 503, no fake data
- API composition is fresh but fan-out/availability sensitive
- Event-fed read model is likely scaling evolution
