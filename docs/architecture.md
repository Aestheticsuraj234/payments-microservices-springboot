# Architecture — the system as it is now

> This page describes the code on the branch you are reading. Every chapter updates it.

## After chapter 2

One service with its own database.

```mermaid
flowchart LR
    client([Client]) -->|HTTP :8081| wallet
    subgraph wallet[wallet-service]
        direction TB
        f[RequestIdFilter] --> c[WalletController]
        c --> s[WalletService]
        s --> r[LedgerRepository]
        c -. exceptions .-> h[ApiExceptionHandler]
    end
    r --> db[(PostgreSQL: wallet)]
```

### Services and infrastructure

| Component | Port | Description |
|---|---|---|
| `wallet-service` | 8081 | Wallets, deposits, balances, entries: the double-entry ledger |
| PostgreSQL 17 | 5433 (host) | One database per service; today only `wallet` |

### wallet-service API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/wallets` | `{"owner_id", "currency"}`; one wallet per owner and currency |
| `GET` | `/v1/wallets/{id}` | System accounts are invisible (404) |
| `GET` | `/v1/wallets/{id}/balance` | Derived from entries |
| `POST` | `/v1/wallets/{id}/deposits` | Debits settlement, credits the wallet |
| `GET` | `/v1/wallets/{id}/entries` | Newest first; `limit` (1–100, default 20) and opaque `cursor` |

### wallet database

| Table / view | Purpose |
|---|---|
| `accounts` | Wallets (`kind = wallet`, have an owner) and system accounts (`settlement` per currency) |
| `transactions` | The journal: one row per movement of money |
| `entries` | Debits and credits; append-only; balanced per transaction at commit |
| `account_balances` (view) | Balances derived from entries |

### Cross-cutting conventions (every service follows them)

- **Errors:** RFC 9457 Problem Details with a stable `code`, `fields` for validation errors, and
  `request_id`. Business code throws sealed `PaymentsException`s; one advice maps them to HTTP.
- **JSON:** snake_case. Money is `{"amount": <minor units>, "currency": "USD"}`.
- **Logs:** every line carries `[service-name,request-id]`.
- **Health:** `/actuator/health/liveness` and `/actuator/health/readiness`.
- **Data:** Flyway migrations, `JdbcClient` SQL, invariants enforced in the database.
- **Tests:** `*Test` run in `mvn test`; `*IT` (full application, PostgreSQL via Testcontainers) in `mvn verify`.

### Package layout (wallet-service)

```
dev.payments.wallet
├── WalletApplication
├── config     WalletProperties
├── error      PaymentsException hierarchy, FieldErrors
├── ledger     Account, Entry, Transaction, Posting, LedgerRepository, WalletService, WalletController, Cursor
├── money      Money, MoneyRules
└── web        ApiExceptionHandler, RequestIdFilter
```
