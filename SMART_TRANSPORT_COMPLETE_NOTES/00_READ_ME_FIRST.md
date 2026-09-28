# Smart Transport Backend — Read Me First

These notes explain the actual eight-service backend at:

`C:\Users\2507178\Desktop\project1\SMART_TRANSPORT_MANAGMENT\backend`

They are written for two audiences at the same time:

- a beginner learning Spring Boot and microservices from a real project;
- a developer preparing to explain design decisions, failures, and improvements in an interview.

## Recommended reading order

1. [Master index and architecture map](01_MASTER_INDEX.md)
2. [Eureka Server](02_EUREKA_SERVER_NOTES.md)
3. [API Gateway](03_API_GATEWAY_NOTES.md)
4. [Auth Service](04_AUTH_SERVICE_NOTES.md)
5. [Route Service](05_ROUTE_SERVICE_NOTES.md)
6. [Vehicle Service](06_VEHICLE_SERVICE_NOTES.md)
7. [Ticket Service](07_TICKET_SERVICE_NOTES.md)
8. [Payment Service](08_PAYMENT_SERVICE_NOTES.md)
9. [Analytics Service](09_ANALYTICS_SERVICE_NOTES.md)
10. [Spring Boot and annotation guide](10_SPRING_BOOT_AND_ANNOTATION_GUIDE.md)
11. [End-to-end flows and interview pack](11_END_TO_END_FLOWS_AND_INTERVIEW_PACK.md)
12. [Exact file-by-file coverage catalog](12_FILE_BY_FILE_COVERAGE_CATALOG.md)

## How to study effectively

For each service, first read its responsibility and flow. Then open its `application.yml`, controller, service implementation, repository, and entity in that order. Finally, use the interview questions without looking at the answers. This connects configuration, HTTP behavior, business logic, and persistence instead of memorizing annotations separately.

## Important project-wide conclusions

- A `.env` file is not automatically loaded by Spring Boot. This repository explicitly imports it with `spring.config.import`; environment-variable names must match the `${NAME:default}` placeholders exactly.
- There is no `bootstrap.yml` in these services, and none is required for the current design. It is mainly associated with early bootstrap configuration such as Spring Cloud Config.
- Gateway-authenticated user headers are trusted only when accompanied by the configured gateway key. Internal analytics and payment endpoints use separate service keys.
- Local database transactions do not make Feign calls or payment-provider effects atomic. Ticket/payment coordination therefore needs idempotency, reconciliation, and ideally an outbox/saga design.
- The red-code problem previously seen in Payment Service was a Maven/IDE module-import problem, separate from runtime `.env` resolution. The payment chapter explains both.

## Service startup order

```text
Eureka (8761)
   ↓
Gateway (9090) + Auth (9091) + Route (9092)
   ↓
Vehicle (9094)
   ↓
Ticket (9093)
   ↓
Payment (9095)
   ↓
Analytics (9096)
```

This is the safest learning/demo order. Eureka registration is dynamic, so some services can technically start in a different order, but calls to unavailable dependencies will fail until those dependencies register and pass health checks.

