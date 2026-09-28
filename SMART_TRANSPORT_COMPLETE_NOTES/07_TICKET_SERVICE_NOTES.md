# Ticket Service — Complete Beginner-to-Interview Notes

## 1. Responsibility and why it is central

Ticket Service owns the booking lifecycle:

- passenger/admin booking;
- authoritative fare and vehicle-capacity checks through other services;
- seat reservation counting;
- PNR generation;
- request idempotency;
- ten-minute pending-payment holds;
- ownership-based access and cancellation;
- Payment Service context/callbacks;
- ticket and route-usage analytics.

It is an **orchestrator**: it combines Route and Vehicle facts into a local ticket. It does not own route definitions, vehicle assignments, users, or money processing.

## 2. Dependency graph and context

```text
Authenticated passenger/admin
        |
        v
Ticket Service
  |          |
  | Feign    | Feign
  v          v
Route      Vehicle
(fare)     (assignment/capacity)
  \\          /
   -> PENDING_PAYMENT ticket + seat hold
                  |
                  v
          Payment Service callbacks
                  |
                  v
         BOOKED / PAYMENT_FAILED / CANCELLED

Analytics Service -> Ticket internal summaries
```

Ticket registers with Eureka on port 9093. Gateway authenticates the external caller; Ticket's filters verify trusted headers and create Spring Authentication. Internal Feign calls propagate the same end-user context plus gateway secret.

## 3. File map

| Area | Files |
|---|---|
| Entry | `TicketServiceApplication` |
| Controllers | `TicketController`, `TicketPaymentController`, `TicketAnalyticsController` |
| Services | Ticket/TicketAnalytics interfaces and implementations |
| External clients | Route/Vehicle Feign clients and two fallback factories |
| Configuration | Feign, Security, Locale |
| Security | gateway/identity filters, Payment and Analytics key validators |
| Persistence | `Ticket`, `TicketStatus`, repository, two projection interfaces |
| DTOs | booking/payment requests; ticket/fare/availability/payment-context/analytics responses |
| Utilities | `BookingRequestHasher`, `TicketNumberGenerator` |
| Mapping | `TicketMapper` |
| Observability | request/response logging filter and Logback config |
| Errors | six domain exceptions and global handler |
| Resources | YAML, four migrations, message bundles |
| Tests | repository, booking, authorization, payment lifecycle, analytics, service-key security |

## 4. Build/startup concepts

The POM adds:

- MVC/Actuator/Security/JPA/Validation/Eureka;
- OpenFeign for declarative Route/Vehicle clients;
- Spring Cloud CircuitBreaker Resilience4j so Feign fallback factories can activate;
- MySQL/Flyway;
- Lombok;
- focused test starters and H2.

`TicketServiceApplication`:

- `@SpringBootApplication`: normal Boot startup/scanning;
- `@EnableFeignClients`: creates Feign proxies;
- `@EnableDiscoveryClient`: explicit discovery activation. With the Eureka starter this is commonly optional, but it documents intent.

## 5. `application.yml` — line-by-line

### Service and trust properties

- port 9093 and loopback bind by default;
- `gateway.secret-key`: validates the trusted gateway/internal call path;
- `payment.internal-secret`: only Payment should call ticket payment endpoints;
- `analytics.internal-secret`: only Analytics should call internal summary;
- current/parent optional `.env` imports;
- discovery name `ticket-service`.

### Database/JPA/Flyway/messages

- MySQL URL default `ticket_db`, required credentials;
- `ddl-auto=validate`, SQL off, formatted if enabled;
- Flyway enabled with baseline settings;
- message bundle basename/UTF-8 plus an explicit `LocaleConfig` bean.

### Feign

- circuit breaker enabled for Feign;
- connect/read timeouts 3s/5s defaults;
- BASIC logger level.

### Eureka and Resilience4j

Separate circuit-breaker instances for `route-service` and `vehicle-service`:

- sliding window: last 10 calls;
- minimum 5 calls before evaluating failure percentage;
- open at 50% failures;
- remain open 10 seconds;
- allow 3 half-open trial calls;
- health indicator registered.

State meanings:

- CLOSED: calls flow and failures are measured.
- OPEN: fail fast without calling dependency.
- HALF_OPEN: limited trial traffic decides recovery.

Actuator exposes health/info/circuitbreakers, but health details are hidden. Logging enables Ticket client and Feign INFO.

No `bootstrap.yml` exists.

## 6. Ticket entity and state machine

### Fields by concern

Identity/owner:

- `ticketId`, unique `pnrNumber`, scalar `userId`.

Journey snapshot/references:

- route/schedule/source/destination IDs;
- service date and departure time;
- assignment and vehicle IDs;
- legacy/combined `travelDate`.

Commercial data:

- passenger count;
- unit fare and total fare.

Idempotency/payment:

- idempotency key + canonical request hash;
- payment hold expiry;
- payment ID and paid time.

Lifecycle/audit:

- status, booking/cancellation timestamps;
- `@Version` optimistic-lock column.

### TicketStatus

```text
PENDING_PAYMENT --success--> BOOKED --refund/cancel--> CANCELLED
       |                         |
       | failure                 +--> USED (enum exists; no endpoint currently marks it)
       v
PAYMENT_FAILED

PENDING_PAYMENT --hold timeout--> EXPIRED
PENDING_PAYMENT --user cancel---> CANCELLED
BOOKED without paymentId --------> CANCELLED
```

`@Version` makes Hibernate add the current version to updates. Two concurrent callbacks editing the same ticket cannot both silently overwrite; one receives an optimistic locking failure. The global handler does not currently translate that exception to a clean 409.

## 7. External TicketController

### Booking

`POST /api/tickets`

- roles PASSENGER or ADMIN;
- requires body validation;
- requires an `Idempotency-Key` in service logic even though header is marked `required=false` to allow a controlled error response;
- returns 201 through `@ResponseStatus`;
- a passenger is bound to their authenticated user ID;
- Admin may specify a different user ID.

### Read/cancel

- by ID and PNR: Passenger/Admin/Manager; service checks ownership unless privileged.
- `/user/{userId}`: same roles, but passengers can only request their own ID.
- `/me`: derives target from Authentication.
- DELETE: Passenger/Admin; paid tickets must use refund flow, and departure must still be future.

Defense in depth: role annotations are broad enough for the endpoint category; service methods enforce row-level ownership.

## 8. Internal payment controller

Path: `/api/tickets/{ticketId}/payment/*`.

Every method requires:

- Passenger or Admin propagated identity;
- correct `X-Payment-Service-Key`;
- ticket owner or Admin access.

Endpoints:

- `/context`: amount, status, expiry, departure, prior payment, payable/refundable flags;
- `/confirmation`: move matching pending ticket to BOOKED;
- `/failure`: move matching pending ticket to PAYMENT_FAILED;
- `/refund`: move matching paid/future ticket to CANCELLED.

Callbacks are idempotent for the same payment ID and already-final state. A duplicate confirmation/failure/refund returns the current ticket rather than repeating the transition.

Important context: Payment Service calls Ticket directly through Feign but propagates the original user's identity. This allows Ticket ownership checks to remain authoritative.

## 9. Internal analytics controller

`GET /api/tickets/internal/analytics?from=&to=` requires Manager/Admin plus analytics secret. It returns status counts, confirmed passenger/fare totals, and route usage for Analytics Service.

## 10. Booking flow in `TicketServiceImpl`

The booking method runs at `Isolation.SERIALIZABLE`.

### Step 1: ownership

- Missing/requested own user → authenticated user.
- Different requested user → only privileged Admin (controller booking allows Admin, so Manager cannot enter this path).

### Step 2: passenger count and idempotency

- normalize missing count to 1, allow 1–10;
- normalize key and require 8–64 characters from `[A-Za-z0-9._:-]`;
- canonicalize owner/journey/count and SHA-256 hash it;
- query `(userId, idempotencyKey)`.

If found:

- same hash → return original ticket;
- different hash → 409 conflict.

This prevents network retries from creating duplicate bookings while detecting accidental key reuse for different requests.

### Step 3: authoritative fare

Feign asks Route Service for fare using route, stops, schedule. Ticket distrusts even a successful response until it verifies all returned IDs, departure, and positive fare.

Then it combines service date + departure and rejects an already departed schedule.

### Step 4: authoritative assignment/capacity

Feign asks Vehicle for route/schedule/date availability. Ticket verifies assigned flag, IDs/date, assignment/vehicle IDs, and positive capacity.

### Step 5: remaining seats

Repository sums passengers for:

- BOOKED and USED;
- PENDING_PAYMENT only while its hold expiry is in the future.

If existing reserved passengers + requested count exceeds vehicle capacity, booking fails 409. Otherwise the new pending ticket holds those seats for ten minutes.

### Step 6: price and persistence

- unit fare rounded to two decimals;
- total = unit × passenger count;
- generate unique PNR;
- snapshot journey/vehicle/fare facts;
- set idempotency/hash;
- hold expiry now + 10 minutes;
- status PENDING_PAYMENT;
- save and map.

### Concurrency discussion

Serializable isolation is intended to stop two transactions from reading the same remaining capacity and both exceeding it. This depends on real MySQL query/index locking and costs throughput. Network calls occur inside the transaction, lengthening locks. Stronger designs include short reservation transactions, a dedicated inventory row locked/atomically decremented, or an event/reservation service.

## 11. Read/cancel/payment service methods

### Access

`verifyTicketAccess` allows owner or privileged (Admin/Manager in read paths). It is not enough to trust a path `userId`; authenticated context is always compared.

### Cancellation

- paid BOOKED ticket with payment ID must refund;
- only BOOKED/PENDING can cancel;
- cannot cancel after departure;
- set CANCELLED, cancellation time, clear hold.

### Lazy hold expiry

`expirePaymentHoldIfNeeded` changes PENDING to EXPIRED when payment context is read after expiry. Capacity counting already ignores an expired-time pending row even before status changes, so capacity is released logically. Analytics/status can still show PENDING until a context read because there is no scheduled expiry job.

### Payment confirmation/failure/refund

- exact `BigDecimal.compareTo` amount match;
- success stores payment ID/time and clears hold;
- failure stores payment ID, sets PAYMENT_FAILED, clears hold;
- refund requires same successful payment, amount, and pre-departure time;
- repeated same callback is idempotent.

Currency is not modeled; the entire platform implicitly assumes one currency.

## 12. DTOs

### Inputs

- `TicketRequestDto`: optional admin target user, positive journey IDs, future/present date, 1–10 passengers.
- `PaymentUpdateRequestDto`: positive payment ID and 0.01+ amount with 2 fraction digits.

### External dependency DTOs

- `FareResponseDto`: mirrors the subset of Route fare response used/validated.
- `VehicleAvailabilityResponseDto`: mirrors Vehicle availability.

### Outputs

- `TicketResponseDto`: full safe booking/payment lifecycle data.
- `TicketPaymentContextDto`: narrow contract Payment uses.
- `TicketAnalyticsDto` and `RouteUsageAnalyticsDto`: internal aggregate contract.
- success/error wrappers.

DTO duplication between services is intentional boundary ownership. A consumer models the contract it depends on. Contract tests/OpenAPI generation can prevent drift.

## 13. Feign clients, fallback factories, and interceptor

### Route client

Calls `/api/routes/{routeId}/fare` with source/destination/schedule query parameters.

### Vehicle client

Calls `/api/vehicles/assignments/availability` with route/schedule/date.

### Fallback factories

Factories receive the root cause, log the dependency/context, and return a client implementation that throws a domain-specific unavailable exception. They do **not** invent a fare or capacity. That is correct: a booking cannot safely continue with guessed business data.

### Request interceptor

- reads SecurityContext;
- forwards user ID and role;
- adds gateway secret;
- sets Feign BASIC logging.

Project-specific issues:

- `log.info("Feign Auth = {}", authentication)` may expose more identity detail than necessary and is noisy.
- If Authentication is missing, interceptor returns and sends no trust headers; downstream rejects, then fallback converts it to 503. Failing immediately with a clear internal error would be easier to diagnose.

## 14. Repository and projections

Derived queries cover PNR, user history, status, idempotency, and PNR existence.

Custom JPQL:

- capacity sum with active hold logic;
- route/status list (currently not central to shown flows);
- grouped status counts/passengers/fares;
- grouped confirmed route usage ordered by passengers.

Projection interfaces map aliases such as `ticketCount`, `passengerCount`, and `fareTotal` to getters without full entity loading.

## 15. Utilities and mapper

### BookingRequestHasher

Builds a delimiter-separated canonical string from all booking semantics and SHA-256 hashes it to 64 hex characters. Because every included value is controlled/simple and `|` cannot appear in numeric/date fields, canonical ambiguity is low.

### TicketNumberGenerator

Creates `TKT-` plus eight uppercase UUID hex characters. Service loops until repository says unique; DB unique constraint is final protection. Collision probability is low but the check/save race can still yield a data-integrity conflict.

### TicketMapper

`@Component` instance mapper exposes every intended response field but never idempotency key/request hash/version. It maps lists with streams.

## 16. Security/observability

Shared filters:

1. require constant-time gateway secret;
2. validate user ID and known role;
3. create `ROLE_*` Authentication;
4. require authenticated request;
5. apply `@PreAuthorize`;
6. internal controllers verify Payment/Analytics secret.

`RequestResponseLoggingFilter`:

- accepts/generates `X-Correlation-Id`;
- places it in SLF4J MDC;
- returns it to client;
- logs method/path/status/duration;
- removes MDC in `finally` to prevent thread-reuse leakage.

`logback-spring.xml` includes `[corr=...]` in console pattern. Feign does not explicitly forward the correlation ID, so cross-service trace continuity is incomplete.

## 17. Exception mapping

- 404 ticket missing;
- 409 invalid state, seat/idempotency/data conflict;
- 503 Route/Vehicle/Feign unavailable;
- 400 bad input/validation;
- 403 ownership/role denial;
- safe 500 fallback.

The handler should log unexpected errors with correlation ID. It should also map optimistic-lock exceptions to 409/retry guidance.

## 18. Flyway history

### V1

Original ticket: PNR, user/route/stops, fare, status enum, dates, version.

### V2

Adds indexes for user, route/status, travel date.

### V3 booking redesign

Adds schedule/service date/departure/passenger/unit fare/assignment/vehicle/idempotency/hash plus:

- unique `(user_id,idempotency_key)`;
- inventory index;
- user/booked index.

### V4 payment lifecycle

Expands status enum with PENDING_PAYMENT/PAYMENT_FAILED, adds hold/payment/paid time, and indexes hold/payment lookup.

Some V3 columns are nullable for backward compatibility with earlier rows even though new service logic expects them. Migration evolution often requires temporarily nullable fields, backfill, then a later tightening migration.

MySQL permits multiple NULL values in a unique index; legacy rows without idempotency keys can coexist.

## 19. Analytics implementation

For up to 366 days:

- groups every status;
- defines BOOKED + USED as confirmed;
- sums confirmed passenger/fare counts;
- returns cancelled/expired/payment-failed counts;
- returns route usage only for confirmed statuses.

`confirmedFareValue` is booking value, not necessarily settled revenue. Payment Analytics is the source for actual captured/refunded money.

## 20. Test map

- repository: counts only BOOKED/USED and unexpired pending passengers;
- booking: fare/group math, idempotent replay/conflict, assigned vehicle, capacity, response mismatch, missing key, cancellation/departure;
- authorization: other-user booking/read/cancel and privileged access;
- payment lifecycle: active/expired holds, confirmation, amount mismatch, failure, refund, cancellation bypass prevention;
- analytics: status/route combinations;
- payment-secret validator: valid, invalid, missing, weak config.

High-value additions:

- real MySQL concurrent capacity/idempotency tests;
- Feign/circuit-breaker integration;
- optimistic-lock callback races;
- scheduled expiry behavior;
- correlation propagation;
- contract tests with Route/Vehicle/Payment;
- USED transition and ticket scanning.

## 21. Best practices and current pitfalls

- Shorten DB transactions by moving safe remote validation outside the inventory lock window, then revalidate/atomically reserve.
- Introduce explicit inventory/reservation model for high scale.
- Add scheduled expiration/status cleanup.
- Model currency.
- Add endpoint/state transition for USED or remove unused state.
- Preserve immutable human-readable route/stop/vehicle snapshots, not only IDs.
- Map optimistic locking to a useful conflict response.
- Propagate correlation ID in Feign.
- Avoid logging Authentication object.
- Consider an outbox/event for ticket-payment state changes and reconciliation.
- Add pagination to histories.
- Define retry policy carefully: idempotency makes booking retry safer, but dependent GET calls and callbacks still need bounded behavior.

## 22. Interviewer favorite questions and answers

### Q1. What is idempotency in booking?

Repeating the same request with the same key produces the same result, not another ticket. The request hash detects reuse of one key with different semantics.

### Q2. Why key idempotency by user and key?

Different users can independently generate the same client key. Within one user's scope, the key uniquely identifies one operation.

### Q3. How are seats reserved before payment?

PENDING_PAYMENT tickets with future `paymentExpiresAt` count toward capacity. After ten minutes they stop counting; successful tickets continue counting.

### Q4. Can a count-then-insert race overbook?

Yes under weak isolation. This project uses SERIALIZABLE to reduce it, but a dedicated inventory row with atomic update/locking is clearer and more scalable.

### Q5. Why validate Feign response IDs?

A 200 response can still be malformed, stale, or mismatched. Ticket verifies that the quotation/availability corresponds exactly to its requested journey before trusting it.

### Q6. What does a fallback do here?

It translates dependency/circuit-breaker failure into a domain 503. It must not fabricate fare/capacity because that would violate correctness.

### Q7. Why use `@Version`?

Optimistic locking detects concurrent stale updates such as success and failure callbacks racing on one ticket.

### Q8. Is this a distributed transaction?

No. Route/Vehicle reads and Ticket write are separate systems with no atomic commit. The service orchestrates and snapshots validated data. Payment synchronization later uses idempotent callbacks/compensation rather than 2PC.

### Q9. What is a Saga?

A Saga coordinates local transactions across services with events/commands and compensating actions. Payment failure/refund and ticket cancellation are examples of states a fuller Saga/reconciliation process would coordinate.

### Q10. Why not retry every failure?

Retries can amplify outages and duplicate non-idempotent effects. Retry only transient failures, with bounds/backoff/jitter, and only when operation semantics/idempotency are safe.

### Q11. Why keep both unit and total fare?

It preserves quotation semantics for group bookings: total equals booking-time unit fare times passenger count and remains auditable if fare rules change.

### Q12. Difference between booking value and revenue?

Ticket confirmed fare is service-demand value. Payment captures/refunds determine financial revenue. Analytics should not confuse the two.

### Q13. Why ownership check in service layer?

Method roles do not identify which row a passenger owns. Service-level authorization stays close to resource loading and applies regardless of controller entry path.

### Q14. How would you reconcile Ticket and Payment disagreement?

Use durable provider/payment records, idempotent commands, an outbox/event log, periodic reconciliation jobs, explicit intermediate states, alerts, and manual repair tooling.

### Q15. What does correlation ID solve?

It links logs from one request across components. It is not tracing by itself; it should be propagated through Feign and ideally paired with distributed tracing spans.

## 23. One-minute revision

- Port 9093; booking orchestrator
- Route fare + Vehicle assignment/capacity
- User-scoped idempotency key and request hash
- Serializable capacity check; 10-minute payment hold
- PENDING → BOOKED/FAILED/EXPIRED/CANCELLED
- `@Version` protects ticket updates
- Payment/Analytics have secret-protected internal endpoints
- Feign circuit breakers fail closed, never invent data
- Correlation-aware logging
