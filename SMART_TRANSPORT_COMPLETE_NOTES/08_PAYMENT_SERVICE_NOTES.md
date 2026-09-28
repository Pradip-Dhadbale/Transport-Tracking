# Payment Service — Complete Beginner-to-Interview Notes

## 1. Responsibility and boundary

Payment Service owns monetary-operation records and coordination:

- validate whether a Ticket is payable/refundable;
- process a charge through a pluggable processor;
- persist payment attempts/results;
- make payment initiation idempotent;
- notify Ticket of success/failure/refund;
- authorize payment history/refunds;
- produce revenue/payment-method analytics.

The current processor is a simulator; no real card/UPI data is collected. This is an important safety boundary: a real integration must use provider tokens/hosted checkout and avoid storing sensitive payment credentials.

## 2. Interconnection with Ticket

```text
Passenger -> POST /api/payments
  -> Payment asks Ticket for payment context
       amount, owner, hold expiry, payable
  -> save PENDING payment
  -> processor charge
  -> callback Ticket confirmation/failure
  -> save SUCCESS/FAILED

Refund
  -> ask Ticket if same payment is refundable
  -> processor refund
  -> callback Ticket refund
  -> save REFUNDED
```

Payment registers as `payment-service` on port 9095. Ticket remains authoritative for ticket owner/state/amount/departure. Payment remains authoritative for provider/payment state.

## 3. File map

| Area | Files |
|---|---|
| Entry | `PaymentServiceApplication` enables Boot/Feign. |
| Controllers | passenger/admin payment API; internal analytics API. |
| Services | Payment and analytics contracts/implementations. |
| Processor strategy | `PaymentProcessor`, `SimulatedPaymentProcessor`, `ProcessorResult`. |
| Ticket integration | `TicketServiceClient`, `FeignConfig`, Ticket context/update DTOs. |
| Persistence | `Payment`, method/status enums, repository, projections. |
| Boundary objects | payment request/response, API wrappers, analytics DTOs. |
| Security | gateway/identity filters and analytics key validator. |
| Utilities | mapper and idempotency request hasher. |
| Errors | five domain exceptions and global handler. |
| Resources/tests | YAML, V1 migration, repository/security/service/analytics tests. |

## 4. `pom.xml` and startup

Standard MVC business stack plus:

- Eureka discovery;
- OpenFeign Ticket client;
- Resilience4j circuit breaker infrastructure;
- JPA/MySQL/Flyway;
- Security/Validation/Actuator;
- Lombok;
- focused test starters and H2.

`@EnableFeignClients` creates the Ticket proxy. Startup validates gateway/payment/analytics secrets, migrates and validates `payment_db`, builds the simulated processor, starts on 9095, and registers with Eureka.

## 5. `application.yml` — line-by-line

### Server/application/database

- port 9095; loopback bind by default;
- current/parent optional `.env` imports;
- discovery name `payment-service`;
- MySQL URL default `payment_db`, required credentials, driver;
- Hibernate validate/no SQL/MySQL dialect;
- Flyway enabled/baseline settings.

### Feign/circuit breaker

- Feign circuit breaker enabled;
- 3-second connect and 5-second read timeout;
- BASIC logging;
- `ticket-service` breaker: 10-call window, minimum 5, open at 50% failures, 10-second open wait, 3 half-open calls;
- Actuator includes circuitbreakers endpoint.

There is no Feign fallback class; service code catches/unwraps failures and maps them to Ticket unavailable or payment-not-allowed.

### Trust and simulation

- `GATEWAY_SHARED_SECRET`: validates user context and is sent to Ticket.
- `PAYMENT_INTERNAL_SECRET`: Ticket verifies Payment identity.
- `PAYMENT_SIMULATOR_OUTCOME`: SUCCESS (case-insensitive) makes all charge/refund calls succeed; every other value makes them fail.
- `ANALYTICS_INTERNAL_SECRET`: protects financial summaries.

No `bootstrap.yml` exists.

## 6. Payment entity and schema

Fields:

- identity payment ID;
- unique transaction reference;
- external ticket ID and scalar user ID;
- exact `DECIMAL(10,2)` amount;
- method: CARD, UPI, WALLET, CASH;
- status: PENDING, SUCCESS, FAILED, REFUNDED;
- user-scoped idempotency key and SHA-256 request hash;
- sanitized failure reason;
- create/update/complete/refund times;
- `@Version` optimistic-lock value.

Constraints/indexes:

- unique transaction reference;
- unique `(user_id,idempotency_key)`;
- `(ticket_id,status)` for successful-payment lookup;
- `(user_id,created_at)` for history.

There is no database FK to Ticket/User because they belong to other services.

## 7. Controller API

Class-level `@PreAuthorize` allows only PASSENGER/ADMIN.

| Endpoint | Behavior |
|---|---|
| `POST /api/payments` | 201; requires idempotency key in service logic; processes ticket payment. |
| `GET /{paymentId}` | Owner or Admin. |
| `GET /me` | Authenticated user's history. |
| `GET /ticket/{ticketId}` | Owner-filtered history or all for Admin. |
| `POST /{paymentId}/refund` | Owner/Admin; validates Ticket and processor refund. |

`PaymentRequestDto` contains only positive ticket ID and required method. Amount is deliberately not accepted from the browser; Payment retrieves it from Ticket, preventing client price tampering.

Internal analytics requires Manager/Admin identity plus `X-Analytics-Service-Key`.

## 8. Payment process flow in detail

`process` currently runs inside a SERIALIZABLE database transaction.

### Step 1: idempotency

- key must be 8–64 safe characters;
- request hash includes authenticated user, ticket ID, and payment method;
- same user/key + same hash returns prior result;
- same key + different request throws 409.

### Step 2: Ticket context

Feign calls Ticket with:

- user ID/role method headers;
- interceptor-added gateway key;
- interceptor-added payment-service key.

Ticket returns amount, owner, state, hold expiry/departure, prior payment, payable/refundable.

Payment verifies:

- matching ticket ID;
- non-null owner and positive amount;
- passenger owns ticket unless Admin;
- payable flag true.

### Step 3: prior success check

If any SUCCESS payment already exists for that ticket, Payment verifies access and returns it rather than charging again. This is a second duplicate-payment defense beyond idempotency key.

### Step 4: local PENDING record

- amount comes from Ticket and is rounded to 2 decimals;
- generate `PAY-` + 32 uppercase UUID hex reference;
- persist/flush PENDING payment to obtain payment ID;
- record method, idempotency, hash.

`saveAndFlush` sends SQL before processor call, but the transaction is still uncommitted. A later rollback can erase the row.

### Step 5: processor

The strategy receives transaction reference, amount, method. The simulator returns a record:

- `successful=true, failureReason=null`; or
- failure with a safe message.

### Step 6: Ticket callback and final local state

- processor success → Ticket confirmation → Payment SUCCESS/completed time;
- processor failure → Ticket failure → Payment FAILED/failure reason;
- callback response must be non-null and successful;
- callback 4xx becomes PaymentNotAllowed;
- network/5xx becomes TicketServiceUnavailable.

### Critical distributed-consistency gap

A local `@Transactional` method cannot roll back an external processor or Ticket transaction.

Examples:

1. Provider charges successfully, Ticket callback fails, Payment transaction rolls back. Money may be captured while no durable local payment remains.
2. Ticket confirmation commits, then Payment's final DB commit fails. Ticket is BOOKED while Payment may remain absent/PENDING.
3. Refund provider succeeds, Ticket callback fails. Retrying can issue a duplicate provider refund if provider-side idempotency is not used.

This is the most important production improvement: persist durable states before external effects, pass idempotency keys to the provider, use an outbox/workflow, retry callbacks safely, and reconcile provider/Ticket/Payment state.

## 9. Refund flow

1. Load payment and verify owner/Admin.
2. If already REFUNDED, return it (local idempotency).
3. Require SUCCESS.
4. Ask Ticket for current refund context.
5. Require refundable, same payment ID, same amount.
6. Call processor refund.
7. Require successful Ticket refund callback.
8. Mark REFUNDED with timestamp.

There is no separate refund operation record/idempotency key/provider refund reference. A real system should model refunds independently because partial/multiple refunds and retries are common.

## 10. Processor strategy pattern

`PaymentProcessor` is an interface with charge/refund. `SimulatedPaymentProcessor` is a Spring component implementing it. Constructor injection into `PaymentServiceImpl` depends on the interface, so provider implementations can change without rewriting orchestration.

If multiple provider beans exist, choose by:

- a routing processor based on method/provider;
- `@Qualifier`;
- map of provider-name to strategy;
- configuration conditions.

`ProcessorResult` is a Java record: immutable data with generated accessors, equality, and compact representation. Static factories express success/failure clearly.

## 11. Feign configuration and contract

`TicketServiceClient` exposes context, confirmation, failure, refund.

- user ID/role are explicit method parameters because callback authorization represents the original caller;
- gateway/payment secrets are automatically added by interceptor;
- the update request contains payment ID and amount;
- callbacks return `ApiResponseDto<Object>` because Payment only needs acceptance, not Ticket details.

`FeignConfig` validates both secrets at startup. Unlike Ticket's interceptor, it does not depend on SecurityContext for secrets. Identity is passed explicitly, which also makes invocation requirements visible.

`role(admin)` returns ADMIN or PASSENGER. Controller only permits those two roles, so the simplification matches current callers.

## 12. Read/access methods

- `get`: load by ID then owner/Admin check.
- `getMine`: user history newest first.
- `getForTicket`: Admin sees all; passenger repository query includes own user ID so it does not reveal another user's records.

When Admin pays another user's ticket, the payment row's `userId` is the Admin's ID even though Ticket owner differs. That may be intentional “payer identity,” but naming should distinguish payer from ticket owner for audit clarity.

## 13. Repository, projections, and analytics

Repository supports idempotency, prior successful ticket payment, user/ticket history, unique reference, and two grouped analytics queries.

Analytics uses half-open date range `[from at 00:00, day after to at 00:00)`—a robust way to include the entire `to` day without precision tricks.

Metrics:

- attempts: all rows including PENDING;
- successful: SUCCESS + REFUNDED (a refunded payment was originally successful);
- failed: FAILED;
- refunded count: REFUNDED;
- gross: SUCCESS + REFUNDED amounts;
- refunds: REFUNDED amounts;
- net: gross − refunds;
- same breakdown per method.

Because queries filter by `createdAt`, a refund performed today on an old payment is attributed to the old payment's creation date when querying that historical range, not to today's refund activity. Financial reporting often needs separate charge/refund event timestamps/ledger entries.

## 14. DTOs, mapper, and hasher

- `PaymentRequestDto`: ticket + method only.
- `TicketPaymentContextDto`: Ticket contract; status is a String to avoid sharing Ticket enum.
- `TicketPaymentUpdateDto`: payment ID/amount callback; it lacks local validation because it is constructed internally.
- `PaymentResponseDto`: safe operational record, no idempotency/hash/version.
- analytics DTOs: total and per-method financial metrics.
- `PaymentMapper`: entity/list mapping.
- `PaymentRequestHasher`: canonical user|ticket|method SHA-256 hex.

No currency appears in request/entity/context/analytics. Add ISO currency code before supporting multi-currency/provider settlement.

## 15. Security

External path:

1. Gateway validates JWT and strips forged internal headers.
2. Payment verifies gateway key.
3. Header filter creates user Authentication.
4. class-level role check limits Passenger/Admin.
5. service verifies ownership.

Analytics path additionally validates analytics secret. Ticket calls additionally use payment secret.

Static secrets are compared constant-time. Production should use TLS/mTLS/workload identity and secret rotation.

## 16. Exceptions and HTTP semantics

- Payment missing → 404.
- idempotency/data conflict → 409.
- invalid/not-allowed/malformed request → 400.
- processor refund failure → 422 Unprocessable Entity.
- Ticket unavailable → 503.
- ownership/analytics denial → 403.
- validation → 400 field map.
- unexpected → logged and safe 500.

A processor **charge decline** is currently recorded as a FAILED payment and the POST still returns 201 with a FAILED resource. That is a defensible “attempt created” model, but the client must inspect status; alternatively return a documented non-2xx domain response.

`unavailable(Exception cause)` currently discards the cause when constructing `TicketServiceUnavailableException`, reducing diagnostic chaining. Preserve the cause in the exception type.

## 17. Migration

One V1 creates:

- every entity column;
- decimal amount;
- string method/status;
- non-null idempotency/hash;
- audit/result timestamps;
- optimistic version default 0;
- two unique constraints and two indexes.

Flyway creates; Hibernate validates.

## 18. Test map

- repository: owner/idempotency/ticket retrieval;
- service: successful charge/callback, decline/failure callback, idempotent replay/conflict, ownership, wrapped Ticket 404, refund, nonrefundable precheck;
- analytics: gross/refund/net math;
- filters: missing gateway key and passenger Authentication.

Critical missing tests:

- provider success + Ticket callback failure;
- Ticket success + local commit failure/outbox recovery;
- duplicate/concurrent refund;
- optimistic locking races;
- real MySQL idempotency concurrency;
- circuit-breaker open behavior;
- provider idempotency/reconciliation;
- analytics by refund event date.

## 19. Best-practice production design

Safer charge workflow:

```text
Transaction A: validate local idempotency; persist PROCESSING/PENDING; commit
Provider call: use stable transaction reference as provider idempotency key
Transaction B: persist provider result + outbox event; commit
Worker: deliver idempotent Ticket update; retry with backoff
Ticket: accept same payment update idempotently
Reconciler: compare provider, Payment, Ticket states
```

Also:

- never store PAN/CVV/UPI secrets;
- use provider-hosted/tokenized payment instruments;
- encrypt sensitive provider references;
- verify signed webhooks and handle replay;
- model provider/payment/refund IDs and raw provider status safely;
- maintain immutable ledger events;
- restrict financial logs/data;
- add currency/rounding policy;
- separate payer and ticket owner;
- implement key/secret rotation.

## 20. Interviewer favorite questions and answers

### Q1. Why is `@Transactional` not enough for payment + Ticket?

It covers only Payment's database. Provider and Ticket have separate transactions, so they cannot be rolled back atomically. Use durable workflow/outbox/idempotency/compensation/reconciliation.

### Q2. What is the exactly-once problem?

Networks can lose responses after an effect occurred, so callers cannot know whether to retry. “Exactly once” is usually approximated with at-least-once delivery plus idempotent operations and deduplication.

### Q3. Why does Payment fetch amount from Ticket?

The browser must not choose the price. Ticket owns the booking-time total and state, so Payment retrieves and validates it server-to-server.

### Q4. What are two idempotency layers here?

User/idempotency-key + request hash deduplicates initiation; prior SUCCESS-by-ticket prevents another key from charging the already-paid ticket. Ticket callbacks are also idempotent for the same payment ID.

### Q5. Why persist PENDING before calling provider?

It creates a durable operation/reference for retries/audit. In this code it is only flushed, not committed, so a production design should commit it before the external call.

### Q6. What is an outbox pattern?

Write business state and an event/message row in one local DB transaction. A separate publisher reliably sends it, marking progress, so a crash cannot leave “DB committed but event forgotten.”

### Q7. How would webhook handling work?

Verify provider signature/timestamp, deduplicate event ID, load operation by provider/reference, apply idempotent state transition, persist audit/outbox, and return quickly. Reconcile unexpected order/duplicates.

### Q8. What does `@Version` protect?

Concurrent local updates based on stale versions, such as refund and another transition racing. It does not coordinate with external provider/Ticket state.

### Q9. Gross versus net revenue?

Gross includes originally successful captures, including later-refunded ones. Refunded amount is subtracted to obtain net. A ledger/event model is more accurate for accounting periods.

### Q10. Why no automatic fallback value?

Financial correctness cannot tolerate invented Ticket amounts/states. Dependency failure should fail closed with 503 and retry/reconcile safely.

### Q11. How do you secure a payment service?

Minimize PCI scope, tokenize instruments, TLS/mTLS, strong service identity, least privilege, secret manager/rotation, encrypted data, signed webhooks, audit logs, rate/fraud controls, and never log sensitive credentials.

### Q12. What if refund succeeds at provider but response times out?

Do not blindly issue a new refund. Query provider by stable idempotency/reference, consume webhook/reconciliation, and make local retry idempotent.

### Q13. Why use SERIALIZABLE here?

It attempts to serialize duplicate local payment/refund decisions, but it is expensive and cannot serialize external systems. DB uniqueness/idempotency/locking plus short local transactions are preferable.

### Q14. Should a declined payment return 201?

If the API models a payment **attempt resource**, 201 with `status=FAILED` can be valid if documented. If it models successful payment as the operation, a 4xx/422-style response may be clearer. Consistency matters.

## 21. One-minute revision

- Port 9095; owns payment attempts/results
- Amount/state come from Ticket, not browser
- User-scoped idempotency and prior-success guard
- PENDING → SUCCESS/FAILED → possible REFUNDED
- Strategy-based simulated processor
- Ticket callbacks update booking state
- `@Version`, unique keys, projections
- Critical production need: committed workflow/outbox/provider idempotency/reconciliation
