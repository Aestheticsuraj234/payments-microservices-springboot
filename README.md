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
| 05 | [The auth service](docs/05-auth-service.md) | `ch05-auth-service` | A second service, database per service, Argon2, JWT, refresh tokens | ✅ |
| 06 | [The API gateway](docs/06-api-gateway.md) | `ch06-api-gateway` | Spring Cloud Gateway, JWT at the edge, Redis rate limits | ✅ |
| 07 | [Events and the outbox](docs/07-events-outbox.md) | `ch07-events-outbox` | Kafka, the transactional outbox pattern, a relay | ✅ |
| 08 | [The transfer service and sagas](docs/08-transfer-saga.md) | `ch08-transfer-saga` | Service-to-service HTTP, holds, an orchestrated saga with compensation | ✅ |
| 09 | [The notification service](docs/09-notification-service.md) | `ch09-notification-service` | Idempotent consumers, retries, a dead-letter topic, email | ✅ |
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
docker compose up -d --wait        # local infrastructure: PostgreSQL (port 5433), Redis, Kafka, Mailpit
./scripts/dev-keys.sh              # a local signing key for auth-service, in secrets/
./mvnw -q install -DskipTests
java -jar auth-service/target/auth-service-1.0.0-SNAPSHOT.jar --auth.private-key-location=file:secrets/auth-signing-key.pem &
java -jar wallet-service/target/wallet-service-1.0.0-SNAPSHOT.jar &
java -jar transfer-service/target/transfer-service-1.0.0-SNAPSHOT.jar &
java -jar notification-service/target/notification-service-1.0.0-SNAPSHOT.jar &
java -jar gateway/target/gateway-1.0.0-SNAPSHOT.jar &
```

Clients use the gateway on port 8080: start with [`api/gateway.http`](api/gateway.http).
Emails appear in Mailpit at http://localhost:8025.


## License

[MIT](LICENSE). The Go reference in `reference/go` is the work of its original author, under the same license.
This is teaching code: treat it as a starting point, not a drop-in system for moving real money.
