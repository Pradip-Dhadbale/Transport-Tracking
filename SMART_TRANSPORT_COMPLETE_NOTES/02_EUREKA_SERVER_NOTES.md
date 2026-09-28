# Eureka Server — Complete Beginner-to-Interview Notes

## 1. Why this service exists

Eureka Server is the system's **service registry**. A registry answers: “Which running instance currently provides `ticket-service`, and at what host/port can I reach it?”

Without discovery, Gateway and Feign clients would need hard-coded URLs such as `http://localhost:9093`. With Eureka they use logical names such as `lb://ticket-service` or `@FeignClient(name = "ticket-service")`.

Eureka is not:

- an API Gateway;
- a reverse proxy for browser traffic;
- an authentication server;
- a business service;
- a database of routes, tickets, or users.

Its only production Java class enables and starts the registry.

## 2. Position in the architecture

```text
                    register + heartbeat
Auth / Route / Ticket / Vehicle / Payment / Analytics
                         |
                         v
                  Eureka Server :8761
                         ^
                         |
              Gateway fetches registry

Gateway: lb://ticket-service
Feign:   name = "ticket-service"
                 |
                 +--> load balancer selects a registered instance
```

Recommended local startup order:

1. Eureka Server
2. Auth, Route, Vehicle, Ticket, Payment, Analytics
3. API Gateway

Services can start while Eureka is temporarily unavailable, but discovery calls will fail until registration and registry refresh succeed.

## 3. File-by-file explanation

### `pom.xml`

- `spring-boot-starter-parent` supplies compatible plugin and dependency defaults.
- `java.version=17` tells Maven to compile for Java 17.
- `spring-cloud.version=2025.1.2` selects the Spring Cloud BOM.
- `spring-cloud-starter-netflix-eureka-server` brings the server implementation and its web infrastructure.
- `spring-boot-devtools` is a runtime-only, optional development convenience. It should normally not be packaged or enabled in production.
- `spring-boot-starter-test` provides JUnit, AssertJ, Mockito, and Spring testing support.
- `spring-cloud-dependencies` is imported as a BOM under `dependencyManagement`. A BOM manages versions; it does not itself add runtime classes.
- `spring-boot-maven-plugin` creates an executable Spring Boot JAR and supports `spring-boot:run`.

Spring concept — parent versus BOM:

- A Maven parent can supply properties, dependency management, and plugin management.
- An imported BOM mainly manages dependency versions.
- This project uses the Boot parent plus the Spring Cloud BOM so individual Cloud dependencies do not need explicit versions.

### `TransportSysProjectApplication.java`

```java
@SpringBootApplication
@EnableEurekaServer
public class TransportSysProjectApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransportSysProjectApplication.class, args);
    }
}
```

Line-by-line meaning:

- `@SpringBootApplication` combines configuration, auto-configuration, and component scanning.
- Because the class is in `com.transport.eurekaserver`, scanning begins at that package and its children.
- `@EnableEurekaServer` imports Eureka server configuration and creates the registry/dashboard endpoints.
- `main` is the JVM entry point.
- `SpringApplication.run(...)` creates the application context, applies configuration, starts the embedded server, and returns the running context.

There is no `@EnableEurekaClient` here. This application is the registry server and explicitly does not register itself in the current single-node configuration.

### `application.yml` — line-by-line

| YAML path | Project value | Meaning |
|---|---|---|
| `server.port` | `${EUREKA_PORT:8761}` | Use `EUREKA_PORT`; otherwise listen on 8761. |
| `server.address` | `${SERVICE_BIND_ADDRESS:127.0.0.1}` | Bind to loopback by default, so other machines cannot connect. Use a suitable interface in a deployed environment. |
| `spring.config.import[0]` | `optional:file:.env[.properties]` | Try a `.env` file in the process working directory, parsed as Java properties. Missing file does not fail startup. |
| `spring.config.import[1]` | `optional:file:../.env[.properties]` | Also try the parent directory. This supports starting inside a service folder. |
| `spring.application.name` | `eureka-server` | Logical application name. |
| `eureka.instance.hostname` | `${EUREKA_HOSTNAME:localhost}` | Hostname used when constructing the server's own Eureka URL. |
| `eureka.client.register-with-eureka` | `false` | Do not behave as a client and register this single server with itself. |
| `eureka.client.fetch-registry` | `false` | Do not fetch a registry from another Eureka peer. |
| `eureka.client.service-url.defaultZone` | constructed URL | Defines the registry endpoint. Here it becomes `http://localhost:8761/eureka/` by default. |
| `eureka.server.eviction-interval-timer-in-ms` | default 10000 | Scan for expired leases every 10 seconds. Aggressive for learning/local use; production values require care. |
| `eureka.server.enable-self-preservation` | default true | Avoid mass eviction when heartbeat traffic falls abnormally. |
| `logging.level.com.netflix.eureka` | `INFO` | Registry-side Eureka logs. |
| `logging.level.com.netflix.discovery` | `INFO` | Discovery client-related logs. |

Important `.env` rule: `${NAME:default}` means “use the property named `NAME`, or the default after the colon.” The `.env` file is not magic; the two `spring.config.import` entries explicitly make it loadable.

No `bootstrap.yml` exists. Older Spring Cloud designs often used bootstrap configuration for properties that had to be loaded before `application.yml`, especially a Config Server. This project has no Config Server, so `application.yml` plus `spring.config.import` is sufficient.

### `TransportSysProjectApplicationTests.java`

- `@SpringBootTest` asks Spring Boot to create the real application context.
- `@Test` marks the JUnit test method.
- An empty `contextLoads()` test succeeds only if startup configuration and bean creation succeed.
- It is a smoke test, not a behavioral test of registration, leases, or eviction.

## 4. What happens during registration

1. A client service reads its `spring.application.name`.
2. The Eureka starter creates a discovery client through auto-configuration.
3. The client sends registration metadata to `defaultZone`.
4. The registry stores an instance record containing service name, instance ID, host, port, and status.
5. The client renews its lease with periodic heartbeats.
6. Other clients periodically fetch/cache the registry.
7. Gateway/Feign asks the load balancer for instances of a logical service name.
8. The load balancer selects one instance and performs the HTTP call.

The registry is eventually consistent. A newly started/stopped service may take a short time to appear/disappear in another client's cached view.

## 5. Lease, eviction, and self-preservation

- A **lease** is the registry's time-limited belief that an instance is alive.
- A **heartbeat/renewal** extends that lease.
- **Eviction** removes an instance whose lease expired.
- **Self-preservation** suspects a network problem when too many renewals disappear together and avoids deleting large parts of the registry.

Self-preservation improves availability but can temporarily leave stale instances. Clients therefore also need sensible connection timeouts, circuit breakers, and retries only where safe.

## 6. How later services use this chapter

- Gateway route `lb://payment-service` resolves through discovery.
- `@FeignClient(name = "route-service")` in Vehicle/Ticket/Analytics resolves through discovery.
- Spring Cloud provides client-side load balancing; Eureka returns candidates but does not proxy the business request.
- Service name consistency matters. `spring.application.name`, `@FeignClient(name=...)`, and Gateway `lb://...` must refer to the same logical registration (case is often normalized, but consistent lowercase names reduce confusion).

## 7. Best practices and project-specific pitfalls

- Run more than one Eureka peer in a real high-availability environment; this project is a single point of failure.
- Do not expose the dashboard/registry publicly without network controls and authentication.
- Do not disable self-preservation in production merely to remove stale local instances faster.
- Bind address `127.0.0.1` is safe for one-machine development but prevents access from containers or other hosts.
- The 10-second eviction scan is useful for demos but can create churn under unstable networks.
- Add explicit Actuator health configuration if operations need standardized health probes.
- The artifact's `<name>` is `TRANSPORT_SYS_PROJECT`, while the discovery name is `eureka-server`. Maven display names and discovery names serve different purposes, but consistent naming would be clearer.

## 8. Troubleshooting checklist

If a service does not appear in Eureka:

1. Confirm Eureka is reachable at the configured `EUREKA_URL`.
2. Confirm the client has the Eureka client starter.
3. Confirm `spring.application.name` is present.
4. Check that the bind/advertised hostname is reachable from other processes.
5. Look for connection-refused or registration errors in client logs.
6. Check whether `.env` resolved a different URL than expected.
7. Wait for registration/registry refresh rather than assuming it is instantaneous.

If Gateway returns 503:

- verify the target service is registered and `UP`;
- verify Gateway fetched the current registry;
- verify logical names match;
- verify the target instance's advertised host/port is reachable.

## 9. Interviewer favorite questions and model answers

### Q1. What problem does Eureka solve?

It removes the need to hard-code changing service locations. Instances register under logical names, clients fetch the registry, and a client-side load balancer selects an available instance.

### Q2. Is Eureka a load balancer?

Eureka is primarily the registry. Spring Cloud LoadBalancer uses the instance list obtained through discovery to select a target. Eureka itself does not proxy the business request.

### Q3. Why are `register-with-eureka` and `fetch-registry` false here?

This is a single-node registry. It does not need to register with or fetch from a peer. A clustered Eureka design would configure peer URLs differently.

### Q4. What is self-preservation?

When the observed heartbeat rate drops sharply, Eureka assumes the network may be partitioned and avoids evicting many instances. It favors availability, at the cost of possibly serving stale entries.

### Q5. What happens when Eureka goes down?

Already-running clients may temporarily use cached registry data, but new discovery updates and registrations fail. Calls can eventually fail as instances change. Eureka should be deployed redundantly and clients still need resilience.

### Q6. Why is `@EnableEurekaClient` not on every business-service main class?

With the client starter and modern Spring Cloud auto-configuration, discovery is often activated from the classpath and properties. `@EnableDiscoveryClient` can be explicit but is not always required.

### Q7. What is eventual consistency in service discovery?

Registration, heartbeat, eviction, and client registry fetch happen periodically, not as one atomic global operation. Different clients may briefly see different instance lists.

### Q8. How would you secure Eureka?

Place it on a private network, restrict inbound access, use TLS and authentication where supported/configured, avoid exposing the dashboard publicly, and apply infrastructure-level network policies.

## 10. One-minute revision

- Port: 8761
- Main annotation: `@EnableEurekaServer`
- Purpose: logical-name-to-instance registry
- Clients register and heartbeat
- Consumers fetch/cache the registry
- Load balancing occurs in clients
- Server does not register with itself in this single-node setup
- Self-preservation protects against mass eviction
- Gateway and Feign depend on discovery
