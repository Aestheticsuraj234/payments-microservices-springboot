# Payments Microservices with Spring Boot — a course in chapters

A payments backend built **one concept at a time** with Java 21, Spring Boot 4 and Spring Cloud.
Every chapter is a branch, a pull request and a document. Read the chapters in order, or check out
any chapter's branch to see the code exactly as it was at the end of that chapter.

The design is ported from a production-grade Go payments system (kept in [`reference/go`](reference/go)).
We reuse its database schemas, its API contracts and its rules about money, and rebuild them the
Spring way, explaining every decision along the way.

## Chapters

| # | Chapter | Branch | What you build | Status |
|---|---------|--------|----------------|--------|
| 00 | [Course setup](docs/00-course-setup.md) | `ch00-course-setup` | The Go reference, the roadmap, the workflow | ✅ |
| 01 | [Spring Boot foundations](docs/01-spring-boot-foundations.md) | `ch01-spring-boot-foundations` | Maven multi-module build, wallet-service skeleton, config, errors, logging | ✅ |
| 02 | [The double-entry ledger](docs/02-double-entry-ledger.md) | `ch02-double-entry-ledger` | Flyway, `JdbcClient`, wallets, deposits, balances, pagination, Testcontainers | ✅ |
| 03 | [Moving money safely](docs/03-safe-transfers.md) | `ch03-safe-transfers` | Row locks, lock ordering, retries, concurrency tests | ✅ |
| 04 | [Idempotency keys](docs/04-idempotency.md) | `ch04-idempotency` | Exactly-once money movement over an unreliable network | ✅ |
| 05 | The auth service | `ch05-auth-service` | A second service, database per service, Argon2, JWT, refresh tokens | planned |
| 06 | The API gateway | `ch06-api-gateway` | Spring Cloud Gateway, JWT at the edge, Redis rate limits | planned |
| 07 | Events and the outbox | `ch07-events-outbox` | Kafka, the transactional outbox pattern, a relay | planned |
| 08 | The transfer service and sagas | `ch08-transfer-saga` | Service-to-service HTTP, holds, an orchestrated saga with compensation | planned |
| 09 | The notification service | `ch09-notification-service` | Idempotent consumers, retries, a dead-letter topic, email | planned |
| 10 | Resilience | `ch10-resilience` | Timeouts, retries, circuit breakers, bulkheads | planned |
| 11 | Observability | `ch11-observability` | Distributed tracing, metrics, dashboards | planned |
| 12 | Shipping it | `ch12-shipping` | Container images, the whole system in Compose, CI | planned |

## Prerequisites

- Java 21
- Docker (for PostgreSQL, Redis, Kafka and the integration tests)
- Maven is optional: the project ships a Maven wrapper

## Build and test

```bash
./mvnw verify                      # every module: unit tests, then integration tests (needs Docker)
docker compose up -d --wait        # local infrastructure (PostgreSQL on port 5433)
./mvnw -pl wallet-service spring-boot:run
```

Example requests are in [`api/`](api).

## License

[MIT](LICENSE). The Go reference in `reference/go` is the work of its original author, under the same license.
This is teaching code: treat it as a starting point, not a drop-in system for moving real money.
