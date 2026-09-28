# End-to-End Flows, Architecture Reference, and Interview Pack

## 1. System reference table

| Order | Service | Port | Owns | Calls |
|---:|---|---:|---|---|
| 1 | Eureka | 8761 | service registry | none |
| 2 | API Gateway | 9090 | edge routing/JWT trust headers | Eureka + all routed services |
| 3 | Auth | 9091 | users, OTPs, refresh tokens | none |
| 4 | Route | 9092 | routes, stops, schedules, fare | none |
| 5 | Vehicle | 9094 | fleet, assignment, location | Route |
| 6 | Ticket | 9093 | bookings, holds, ticket lifecycle | Route, Vehicle |
| 7 | Payment | 9095 | charges/refunds/payment records | Ticket |
| 8 | Analytics | 9096 | live compositions/report snapshots | Ticket, Payment, Vehicle, Route |

## 2. Data ownership matrix

| Data | Owner | Other services store/use |
|---|---|---|
| User identity/role | Auth | scalar user ID and trusted request role |
| Route/stop/schedule | Route | scalar IDs and booking/assignment snapshots |
| Vehicle/assignment/location | Vehicle | Ticket stores selected assignment/vehicle IDs |
| Ticket/seat hold | Ticket | Payment stores ticket ID and reads context |
| Payment/refund | Payment | Ticket stores successful payment ID/state |
| Saved report | Analytics | no other DB access |

Database-per-service means no cross-service SQL joins or FKs. APIs/events preserve boundaries.

## 3. Startup flow

```text
1. MySQL available
2. Eureka starts :8761
3. Auth/Route/Vehicle/Ticket/Payment/Analytics start
   - load .env/config
   - Flyway migrations
   - Hibernate validation
   - register with Eureka
4. Gateway starts :9090 and fetches registry
5. Frontend calls only Gateway
```

Startup dependency does not mean every service must crash if Eureka starts later; clients retry registration. But application workflows require dependencies before use.

## 4. Registration and authentication flow

```text
POST /auth/register/send-otp
 -> Auth rejects duplicate
 -> SecureRandom OTP -> BCrypt hash -> DB
 -> console/provider delivery

POST /auth/register/verify-otp
 -> expiry/attempt/BCrypt checks
 -> consume OTP
 -> REGISTRATION JWT (10 min, subject=mobile)

POST /auth/register/complete + registration token
 -> create PASSENGER + BCrypt password
 -> ACCESS JWT + REFRESH JWT
 -> store only SHA-256 refresh hash
```

Later:

```text
Bearer ACCESS JWT
 -> Gateway verifies signature/expiry/type/role/subject
 -> replaces X-User-Id/Role/Gateway-Key
 -> downstream verifies gateway key
 -> creates Authentication
 -> role and ownership authorization
```

## 5. Admin transport setup

```text
Manager/Admin create Route
 -> add ordered Stops with cumulative distance
 -> add normalized operating Schedules

Manager/Admin create Vehicle
Dispatcher creates dated Assignment
 -> Vehicle checks local conflicts
 -> Vehicle validates Route + Schedule + operating date by Feign
```

Invariants:

- different route endpoints;
- unique route name (service level; DB improvement recommended);
- unique stop sequence and increasing distance;
- schedule arrival after departure/current model;
- no duplicate departure on overlapping days;
- no vehicle/date or schedule/date active assignment conflict.

## 6. Search and booking flow

```text
Passenger searches Route from A to C/date
 -> Route JPQL finds A before C
 -> filters schedules by day
 -> returns segment distance + estimated fare

POST Ticket with Idempotency-Key
 -> bind owner to JWT subject
 -> same key/hash replay returns prior ticket
 -> Feign Route exact fare for chosen stop/schedule
 -> Feign Vehicle active assignment/capacity
 -> count BOOKED/USED + unexpired pending passengers
 -> serializable capacity decision
 -> create PENDING_PAYMENT, hold expires in 10 min
```

Failure choices:

- Route unavailable → 503, no booking;
- Vehicle unavailable → 503, no booking;
- no assignment/capacity → 409;
- reused key/different request → 409;
- duplicate same request → original response.

## 7. Payment flow

```text
POST Payment with new Idempotency-Key
 -> Payment asks Ticket context
 -> Ticket verifies owner/admin + payment service key
 -> amount/hold/payable returned
 -> Payment saves/flushed PENDING
 -> processor charge

success:
  Payment -> Ticket confirmation -> Ticket BOOKED
  Payment -> SUCCESS

decline:
  Payment -> Ticket failure -> Ticket PAYMENT_FAILED
  Payment -> FAILED
```

The current synchronous transaction cannot make these remote effects atomic. Production requires durable operation states, provider idempotency, outbox/retries, and reconciliation.

## 8. Refund flow

```text
POST /payments/{id}/refund
 -> owner/Admin and SUCCESS check
 -> Ticket context: matching payment, amount, BOOKED, before departure
 -> provider refund
 -> Ticket refund callback -> CANCELLED
 -> Payment -> REFUNDED
```

Normal Ticket cancellation cannot cancel a paid ticket, preventing bypass of refund processing.

## 9. Analytics/report flow

```text
usage       -> Ticket grouped status/route metrics
revenue     -> Payment grouped status/method metrics
performance -> Vehicle completed assignments
               -> Route schedule per unique pair
               -> actual complete <= scheduled arrival + grace

generate report
 -> call all three
 -> persist selected snapshot with generator/period
```

One source failure fails complete report generation. Live composition is eventually consistent because sources are read at different times.

## 10. Security trust boundaries

### External boundary

- browser sees Gateway only;
- Gateway validates JWT;
- forged internal headers are removed;
- CORS is browser policy, not backend authentication.

### Downstream boundary

- business services require gateway key;
- validate positive user ID/known role;
- apply role and ownership checks.

### Internal caller boundary

- Payment callbacks require Payment secret;
- analytics source endpoints require Analytics secret;
- original Manager/Admin or Passenger/Admin context is still propagated.

Production improvement: private networking, TLS/mTLS, per-workload short-lived identity, secret manager, rotation, audit trails.

## 11. Failure-mode table

| Failure | Current behavior | Stronger design |
|---|---|---|
| Eureka down | cached discovery may work temporarily; new changes fail | clustered registry, health/alerts |
| Route down during booking | breaker/fallback → 503 | retry only safe reads, cached/versioned reference where valid |
| Vehicle down | 503, no booking | same; never guess capacity |
| Concurrent ticket booking | SERIALIZABLE + indexed count | atomic inventory/reservation row |
| Ticket hold expires | ignored in capacity; status lazily becomes EXPIRED | scheduled cleanup/event |
| Provider charge + callback failure | transaction may rollback despite external effect | committed workflow/outbox/reconciliation |
| Duplicate request | idempotency key/hash | retain key/result per policy |
| Conflicting callbacks | Ticket/Payment `@Version` | explicit state machine + retry classification |
| Analytics source down | 503/no report | completeness flags or event-fed read model |
| `.env` typo | missing/default property and startup/runtime error | typed required config validation |

## 12. Observability checklist

Current:

- Actuator health/info;
- circuitbreaker endpoint where configured;
- Gateway route logging;
- Ticket correlation ID and duration logs;
- safe exception responses in most services.

Recommended:

- propagate correlation ID through every Feign interceptor;
- OpenTelemetry traces with one span per service/provider call;
- metrics for latency/error/circuit state/booking conflicts/holds/payment reconciliation;
- structured JSON logs;
- never log JWTs, OTPs, passwords, shared secrets, provider credentials;
- alerts for mismatch/stuck PENDING states.

## 13. Testing strategy for the whole system

### Unit

- fare math, day parsing, hashing, state transitions, authorization decisions.

### Slice/integration

- repository queries and constraints;
- security filters/controllers;
- Flyway + JPA with MySQL Testcontainers.

### Contract

- Route fare response expected by Ticket;
- Vehicle availability expected by Ticket;
- Ticket context/callback expected by Payment;
- internal analytics contracts.

### End-to-end

1. register/login;
2. create route/stops/schedule;
3. create/assign vehicle;
4. book with idempotency retry;
5. pay with retry;
6. read ticket/payment;
7. refund;
8. generate report.

### Chaos/failure tests

- dependency timeout;
- callback response loss after commit;
- process crash between state steps;
- duplicate callbacks;
- concurrent last-seat bookings;
- Eureka/database restart.

## 14. Architecture best-practice roadmap

### Immediate correctness/security

- fix OTP logging false branch;
- require JWT secret without weak default;
- standardize password rules;
- preserve causes/log diagnostics safely;
- add optimistic-lock HTTP translation;
- add provider-side payment/refund idempotency.

### Reliability

- outbox/workflow for Payment↔Ticket;
- reconciliation jobs;
- short local transactions;
- explicit seat inventory/reservation;
- scheduled hold expiry;
- correlation/tracing.

### Scalability

- pagination everywhere;
- batch schedule analytics;
- GPS streaming/time-series retention;
- event-fed analytics read model;
- cache stable reference data carefully;
- per-service autoscaling and DB capacity planning.

### Maintainability

- typed validated configuration;
- API specifications/contract tests;
- consistent error envelope/status semantics;
- consistent dependency version family;
- remove unused dependencies/classes;
- document state machines and ownership.

## 15. System-design interviewer questions and model answers

### Q1. Why eight services instead of one monolith?

They separate registry/edge/identity and distinct business data ownership. Benefits include independent evolution/scaling/fault isolation; costs include network failure, distributed consistency, operational complexity. For a small team, a modular monolith could be simpler until scale/ownership justifies services.

### Q2. Is this system truly database-per-service?

Yes by design: Auth, Route, Vehicle, Ticket, Payment, Analytics use their own schemas and APIs. Eureka/Gateway have no business database. Cross-service IDs are scalar references.

### Q3. What is the biggest correctness risk?

Payment/provider/Ticket partial failure because local transactions do not cover external effects. Address with durable states, idempotency, outbox/retries, and reconciliation.

### Q4. How do you prevent overbooking?

Current design counts reserved passengers under SERIALIZABLE isolation. A scalable design uses an inventory row/reservation ledger with atomic conditional decrement or locking and expiring holds.

### Q5. Why use synchronous calls?

They are simple when a response requires immediate authoritative validation. Use events for asynchronous propagation, analytics, workflows, and decoupling where immediate response is unnecessary.

### Q6. Where would Kafka/RabbitMQ help?

Payment/Ticket workflow events, analytics ingestion, route/assignment changes, notifications, GPS ingestion. Messaging still requires idempotent consumers, ordering/partition strategy, DLQs, and observability.

### Q7. How would you deploy discovery in Kubernetes?

Kubernetes Services/DNS can replace Eureka for service location, reducing one platform component. Gateway/Feign would use platform discovery/load balancing; retain Eureka only if required by hybrid architecture.

### Q8. How do you handle service versioning?

Backward-compatible contracts, tolerant readers, API/event version strategy, consumer contract tests, staged rollout, database expand-migrate-contract, and observability before removal.

### Q9. What does eventual consistency look like here?

Route may change after Vehicle validates it; report sources are read at different times; Payment/Ticket can temporarily disagree. The system needs explicit states, snapshots/events, and reconciliation rather than assuming immediate global consistency.

### Q10. How would you make JWT logout immediate?

Short access expiry plus refresh revocation is current. Immediate options include token version/security stamp lookup, revocation list/cache, or opaque introspected tokens—each adds availability/latency/state.

### Q11. Why is a shared gateway header not sufficient production security?

Any compromised service knowing the shared value can impersonate gateway/others. Use private network policies, TLS/mTLS, workload identity, per-service authorization, and rotation.

### Q12. How would you scale Analytics?

Move from request-time fan-out to event/CDC-fed analytical storage, precompute aggregates, partition by time/dimension, track freshness, support backfills, and keep owner-service APIs for operational truth.

### Q13. What is the difference between orchestration and choreography?

Orchestration has a coordinator directing steps (Ticket booking, Payment coordination). Choreography lets services react to events without one central coordinator. Orchestration is explicit/easier to trace; choreography decouples but can obscure flow.

### Q14. How do you choose timeout values?

Start from end-to-end latency budget, allocate smaller downstream budgets, measure percentiles, keep connection timeout short, avoid stacked retries, and ensure callers time out after dependencies—not before necessary cleanup.

### Q15. What are bulkheads?

Separate resource pools/concurrency limits so one failing dependency/workload cannot exhaust all threads/connections and take down unrelated operations.

## 16. Practical debugging playbook

### Red imports/annotations in IDE

1. Run Maven compile to distinguish build from IDE issue.
2. Import backend reactor POM.
3. Ensure module uses Maven dependencies and correct JDK target.
4. Reload Maven/annotation processing/Lombok plugin as relevant.
5. Avoid overlapping nested `.idea` project models.

### Placeholder/config errors

1. Read the final root-cause line.
2. Match YAML placeholder name exactly to `.env` key.
3. Check working directory and both import paths.
4. Check OS env overriding `.env`.
5. Safely inspect presence/length, never print secrets.
6. Fully restart service.

### 401

- Gateway Authorization header/token type/signature/expiry;
- gateway/internal header stripping/injection;
- matching gateway secret;
- valid positive ID/known role.

### 403

- authenticated role against `@PreAuthorize`;
- resource ownership;
- Payment/Analytics internal key.

### 503

- Eureka registration/UP state;
- Feign timeout/circuit state;
- dependency logs;
- service-name consistency;
- network/bind address.

### Database startup

- credentials/URL/schema permissions;
- Flyway history and failed migration;
- Hibernate validation mismatch;
- port/locked target file/process on Windows.

## 17. Final rapid revision

```text
Eureka discovers
Gateway routes/authenticates
Auth identifies
Route defines supply
Vehicle assigns capacity
Ticket reserves/books
Payment settles/refunds
Analytics composes/reports
```

The five ideas to remember:

1. Every service owns its data and domain rules.
2. Network calls fail differently from local calls.
3. Idempotency and state machines are core correctness tools.
4. A local transaction never makes distributed effects atomic.
5. Security requires trusted identity, service identity, authorization, and ownership—not only a valid JWT.
