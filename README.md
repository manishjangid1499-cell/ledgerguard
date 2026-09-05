# LedgerGuard — Payment Integrity & Ledger Platform

> **Disclaimer**: This is a portfolio and educational financial-infrastructure system designed to demonstrate correctness-first Java backend engineering, transactional integrity, and distributed systems resilience. It is **NOT** intended to process real money and operates strictly on simulated financial workflows.

---

## 1. Project Overview

**LedgerGuard** is a high-reliability financial core and payment ledger platform designed to solve the hardest problems in financial backend engineering: concurrency contention, double-spending, distributed failure ambiguity, idempotent request processing, immutable auditing, and multi-level ledger reconciliation.

Rather than treating balances as simple mutable counters or wrapping third-party payment gateway APIs, LedgerGuard implements an authoritative **immutable double-entry accounting engine** backed by PostgreSQL ACID transactions, coupled with an asynchronous transactional outbox for post-commit event propagation via Apache Kafka.

---

## 2. Central Financial Invariant

The fundamental principle governing every transaction in LedgerGuard:

$$\text{\bf MONEY MUST NEVER BE CREATED, DESTROYED, DUPLICATED, OR SILENTLY LOST.}$$

For every posted journal transaction across all accounts:

$$\sum \text{DEBITS} = \sum \text{CREDITS}$$

---

## 3. Architecture Summary

LedgerGuard structures its financial core as a **Modular Monolith** (`ledgerguard-api`) to execute multi-account money movement within a single local PostgreSQL ACID transaction boundary. It avoids distributed transactions for core money paths while isolating external network boundaries (payment providers, notification consumers) into dedicated services.

```
+-------------------------------------------------------------+
|                      React Frontend (Vite)                  |
+-------------------------------------------------------------+
                              | (HTTPS / REST)
                              v
+-------------------------------------------------------------+
|                     Nginx Reverse Proxy                     |
+-------------------------------------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|                 LedgerGuard API (Modular Monolith)          |
|  [Identity] [Ledger] [Account] [Transfer] [Payment] [Outbox]|
+-------------------------------------------------------------+
         |                                  |
         | (ACID Transactions)              | (Transactional Outbox)
         v                                  v
+-------------------+              +-------------------+
|    PostgreSQL     |              |   Apache Kafka    |
| (Authoritative DB)|              +-------------------+
+-------------------+                        |
                                             v
+--------------------+             +--------------------+
|   PSP Simulator    |<--(REST)---|Notification Worker |
| (Separate DB/State)|             | (Inbox Deduplicated|
+--------------------+             +--------------------+
```

### Main Deployables (Target Architecture)
1. **`ledgerguard-api`**: Core modular monolith managing identity & authentication, ledger accounts, double-entry journal transactions, transfers, payments, holds, outbox events, and reconciliation.
2. **`psp-simulator`**: Independent banking/payment service simulator with isolated database, modeling realistic network ambiguity (timeouts, delayed webhooks, duplicate callbacks, transient 500s).
3. **`notification-worker`**: Asynchronous event consumer with inbox deduplication and dead-letter handling.
4. **`ledgerguard-web`**: TypeScript/React frontend providing customer banking portals and administrative operations/reconciliation consoles.
5. **`failure-lab`**: Automated chaos-testing and invariant verification suite.

---

## 4. Killer Feature: Money Integrity Failure Lab

The **Money Integrity Failure Lab** is an automated resilience and verification engine that deliberately subjects the system to adverse operating conditions:
- Concurrent opposing transfers (deadlock risk)
- Double-spending attempts under extreme concurrency
- Network response drop after database commit
- Duplicate and out-of-order PSP webhooks
- Kafka broker outages and consumer crashes
- Intentionally corrupted balance snapshot injections

After each failure injection, the engine mathematically proves that:
- Unbalanced journal transactions $= 0$
- Duplicate economic effects $= 0$
- Invalid negative available balances $= 0$
- Unexpected balance snapshot mismatches $= 0$
- Total system currency matches $\sum \text{Opening Balances} + \sum \text{External Inflows} - \sum \text{External Outflows}$.

---

## 5. Major Engineering Areas

- **Identity & Authentication**: Embedded Spring Security architecture, BCrypt password hashing, short-lived HS256 JWT access tokens, high-entropy opaque refresh tokens with SHA-256 hash persistence, dedicated `HttpOnly` / `SameSite=Strict` cookie strategy, and pessimistic row locking for atomic token rotation.
- **Immutable Double-Entry Accounting**: Balanced debit/credit entries; posted transactions are permanent and corrected only through compensating entries.
- **Merchant Payments & Refunds Domain**: Customer-to-merchant commercial transactions (`CREATED -> PROCESSING -> SUCCEEDED / FAILED`), 100 bps integer floor division platform fee policy, and synchronous full and partial payment refunds (`original-payment-pro-rata:v1` telescoping pro-rata fee reversal) backed by immutable compensating double-entry journals (`CREDIT customer refundAmount`, `DEBIT merchant merchantDebitAmount`, `DEBIT platform_fees feeDebitAmount`), cumulative refund cap enforcement, parent payment row serialization (`FOR UPDATE`), and original fee account resolution.
- **Balance Holds & Available-Balance Model**: Temporary fund reservations (`balance_holds`) separating immutable posted ledger history from spendable capacity without altering double-entry journals or snapshots, database triggers enforcing immutability and capacity under snapshot row locks `FOR UPDATE`, available balance decomposition (`availableBalance = postedBalance - sum(ACTIVE holds)`), spending paths validation, and multi-instance safe background expiration (`HoldExpirationScheduler`).
- **Concurrency Control**: Deterministic account lock ordering (lower identifier first) to prevent opposing-transfer circular-wait deadlocks, serialize refund attempts on parent payment rows, serialize hold reservations on snapshot rows, and prevent double-spending under concurrent workloads.
- **Authoritative Idempotency**: Atomic database-backed request deduplication keys with cryptographic payload fingerprinting.
- **Transactional Outbox & Inbox**: Multi-worker transactional outbox persistence (`outbox_events`) ensuring atomic financial outcome event durability within the same PostgreSQL transaction, with multi-worker `SKIP LOCKED` publisher to Kafka in Phase 17 and idempotent consumer inbox processing in Phase 18.
- **External Funding & Payouts Domain**: Integration with external PSP simulator for wallet top-ups (Phase 20) and outbound payouts/withdrawals (Phase 21) using pre-network balance hold reservation, decoupled non-transactional HTTP client calls, confirmed-success hold consumption and double-entry settlement (`DEBIT source wallet, CREDIT PSP_CLEARING`), definite-failure hold release, and in-flight hold expiration protection.
- **Ambiguous External Outcomes & Status Recovery (Phase 23)**: Formal six-state external lifecycle (`CREATED → PROCESSING → UNKNOWN → RECONCILIATION_REQUIRED → SUCCEEDED/FAILED`) enforced by Flyway V13 PostgreSQL triggers. Atomic submission claim (at-most-one provider POST via pessimistic row lock). RFC-9457 ProblemDetail `type` URI classification: `urn:ledgerguard:psp:error:temporary-failure` → definite `FAILED` (hold released); generic 500/timeout → `UNKNOWN` (hold `ACTIVE`). Durable background status poller (Step 0 exhaustion finalizer + `SKIP LOCKED` claim + non-transactional GET + settlement). Payout hold protection extended to `UNKNOWN` and `RECONCILIATION_REQUIRED`. Late webhook recovery (`RECONCILIATION_REQUIRED → SUCCEEDED/FAILED`). **`UNKNOWN != FAILED`: ambiguous outcomes must never be silently treated as financial failures.**
- **Three-Level Reconciliation (Phase 24)**: Automated three-level, detection-only reconciliation engine backed by Flyway V14 migration triggers and two-phase locking serialization. Level 1 (`JournalBalanceChecker`) verifies double-entry invariants via unbounded `NUMERIC` aggregation and `LEFT JOIN` (detecting unbalanced and zero-entry malformed journals); Level 2 (`SnapshotConsistencyChecker`) verifies derived balance snapshot consistency against immutable `POSTED` journal history in a single MVCC statement (excluding `DRAFT` entries via subquery); Level 3 (`ProviderSettlementChecker`) compares internal funding and payout states against external provider truth with network calls strictly outside DB transactions and lock-isolated classification. **Detection-only invariant: zero mutation of financial or business tables.**
- **Reconciliation Recovery & Manual Review (Phase 25)**: Flyway V15 migration introducing `reconciliation_cases` table with null-safe claim immutability (`IS DISTINCT FROM`), `ON DELETE RESTRICT` actor preservation, and automated case creation triggers on item detection. Automated balance snapshot repair exclusively restricted to `problem_type = SNAPSHOT_MISMATCH` via dynamic reconstruction from `POSTED` journals under pessimistic row lock (`FOR UPDATE`), with 64-bit integer bounds check, missing snapshot protection, and `ALREADY_CONSISTENT` resolution. Human-in-the-loop manual review workflow with mandatory investigation notes ($\le 1000$ chars), strictly separated from financial tables (proven zero mutations to journals, entries, snapshots, holds, funding, or payouts).
- **Resilient Provider Client (Phase 26)**: Programmatic core Resilience4j 2.4.0 integration (`resilience4j-circuitbreaker`, `resilience4j-retry`, `resilience4j-bulkhead`) decorating outbound PSP requests without Spring Boot starters or AOP. Pipeline: CircuitBreaker (`psp-remote`) $\to$ Bulkhead (`psp-create` / `psp-status`, 20 permits each) $\to$ Aggregate Logical Outcome $\to$ Exponential Jittered Retry (max 3 attempts) $\to$ Raw `RestClient`. Central financial invariants enforced: safe idempotent POST replay; earlier transport timeouts resolve to `SUCCEEDED` upon authoritative replay (`TIMEOUT_AFTER_SUCCESS`); ambiguity dominance across multi-attempt history marks `UNKNOWN` with `ACTIVE` balance hold; pre-network rejections (circuit open or bulkhead full) fail fast with 0 raw HTTP dispatches, marking `FAILED` and releasing holds; polling retries do not inflate durable database counters; Level 3 reconciliation classifies provider unavailability as `UNRESOLVED` / `PROVIDER_UNAVAILABLE` without schema changes.
- **Rate Limiting & Bounded Backpressure (Phase 27)**: Token-bucket admission control powered by Bucket4j 8.19.0 (`bucket4j_jdk17-core`) backed by bounded Caffeine cache (`maxEntries=10000`, `idleTtl=1h`). RateLimitFilter sits after Spring Security `AuthorizationFilter` ensuring 401 and 403 strictly precede rate limit evaluation. Keyed by IP for public authentication endpoints (`PUBLIC_AUTH:ip:<ip>`: 10/min) and by policy and JWT user UUID for authenticated requests (`FINANCIAL_WRITE:user:<uuid>`: 20/min, `OPS:user:<uuid>`: 30/min, `AUTHENTICATED_GENERAL:user:<uuid>`: 50/min). Bounded request execution via Tomcat worker threads (`max=50`, `min-spare=10`, `max-queue-capacity=50`, `accept-count=50`, `max-connections=1000`) and Hikari connection pool (`maximum-pool-size=10`). Bounded Kafka consumer backpressure on `notification-worker` (`concurrency=3`, `max.poll.records=10`). HTTP 429 returns RFC 9457 ProblemDetail with `RATE_LIMIT_EXCEEDED` and `Retry-After` header. Zero financial mutation on 429: pure admission control with safe idempotent replay after refill.
- **Audit Trail & Security Hardening (Phase 28)**: Database-enforced immutable audit trail (`audit_events` via Flyway V16) protecting privileged administrative actions with database triggers prohibiting `UPDATE`, `DELETE`, and `TRUNCATE`. Strongly typed `AuditService` operating under `Propagation.MANDATORY` atomicity for case claims, manual resolutions, and snapshot repairs (repaired and already consistent), recording zero rows on idempotent replay and rolling back atomically if the business transaction conflicts. Raw control character input hardening (rejecting NUL, CR, LF, TAB, C0 controls, and DEL before trimming or whitespace normalization). Security response headers hardened with explicit Content Security Policy (`default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`), explicit HSTS (`max-age=31536000; includeSubDomains`), preserved `nosniff`/`DENY`, and CORS allowlist with exposed `Retry-After`. Account freeze/unfreeze endpoints deferred per human approval (Option A); codebase audit verified zero PII or credential leaks in logs.
- **Business & Financial Integrity Metrics (Phase 29)**: Standardized Prometheus metric exposition via Micrometer (`io.micrometer:micrometer-registry-prometheus`) exposed at `/actuator/prometheus`. Implements a decoupled-scrape architecture where Prometheus scrapes read directly from in-memory atomics with zero database queries. Financial integrity gauges (`unbalanced_journal_count`, `reconciliation_discrepancies`, `outbox_lag_seconds`) are sampled asynchronously by `IntegrityMetricsSampler` every 15s using a single atomic SQL statement in `IntegrityMetricsSnapshotReader` and published atomically via `AtomicReference<IntegritySnapshot>`. Application-level idempotency counter `duplicate_idempotency_keys_total` records duplicate encounter events partitioned by bounded reason tags (`replay`, `fingerprint_conflict`, `in_progress`). Endpoint is permitted without JWT, exempted from rate limiting, and excluded from CORS. Zero financial or business mutation; migrations V1-V16 frozen, V17 strictly absent.
- **Observability**: Micrometer metrics, Prometheus, Grafana dashboards, OpenTelemetry distributed tracing, and structured logging.

---

## 6. Technology Direction

- **Backend:** Java 21, Spring Boot 4.x, Spring Data JPA / Hibernate, Spring Security, Flyway, Maven.
- **Authoritative Store:** PostgreSQL (`ddl-auto=validate`).
- **Asynchronous Messaging:** Apache Kafka.
- **Frontend:** React 19, TypeScript, Vite 8, Material UI 9, TanStack Query, React Hook Form.
- **Testing:** JUnit 5, Testcontainers (PostgreSQL, Kafka), Mockito, ArchUnit.
- **Infrastructure:** Docker, Docker Compose, Nginx, Prometheus, Grafana, OpenTelemetry.

---

## 7. Current Project Status

- **Current State:** Phase 29 Completed — Business & Integrity Metrics (Prometheus): Implemented decoupled Prometheus metrics exposition at `/actuator/prometheus` via Micrometer. Eagerly registered custom business gauges (`unbalanced_journal_count`, `reconciliation_discrepancies`, `outbox_lag_seconds`) backed by single-statement consolidated PostgreSQL sampling (`IntegrityMetricsSnapshotReader`), atomic in-memory publication (`AtomicReference<IntegritySnapshot>`), fixed-delay background scheduler (`IntegrityMetricsSampler`), and duplicate idempotency counter (`duplicate_idempotency_keys_total` with bounded tags). `/actuator/prometheus` is permitted without auth, rate-limit exempt, and browser-CORS blocked. All financial invariants preserved. Workspace total 688 tests (651 API, 17 PSP, 19 Notification Worker, 1 Failure Lab) with 0 failures, 0 errors, 0 skipped.
- **Next Step:** Phase 30 — OpenTelemetry Tracing & Correlation IDs.
- **Roadmap:** Detailed phase-by-phase progress is tracked in [docs/STATUS.md](docs/STATUS.md).

---

## 8. Local Development Infrastructure

### Prerequisites
- Docker Engine 29+ & Docker Compose v5+
- Java 21 LTS & Node.js 24 LTS

### 1. Configure Environment
```bash
# Copy example environment configuration
# Windows:
Copy-Item .env.example .env

# Linux / macOS:
cp .env.example .env
```

### 2. Manage Local Infrastructure (PostgreSQL & Kafka)
```bash
# Start infrastructure in background
docker compose up -d

# Inspect service health (both services will report healthy)
docker compose ps

# Stop infrastructure (preserves volumes)
docker compose down

# Destructive reset (WARNING: DELETES ALL LOCAL DATABASE AND KAFKA DATA)
docker compose down -v
```

### Local Endpoints & Database Ownership
| Service | Container Name | Host Port | Database / Scope | Owner Role | Owner Deployable |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **PostgreSQL 17.11** | `ledgerguard-postgres` | `5432` | `ledgerguard` | `ledgerguard_app` | `ledgerguard-api` |
| **PostgreSQL 17.11** | `ledgerguard-postgres` | `5432` | `psp_simulator` | `psp_simulator_app` | `psp-simulator` |
| **PostgreSQL 17.11** | `ledgerguard-postgres` | `5432` | `notification_worker` | `notification_worker_app` | `notification-worker` |
| **Apache Kafka 4.3.1 (KRaft)** | `ledgerguard-kafka` | `29092` (host) / `9092` (container) | Broker ID 1 (Cluster ID configured) | — | Outbox event stream |

---

## 9. Build & Verification Commands

### Backend Build & Test (from root)
```bash
# Windows
.\mvnw.cmd clean verify

# Linux / macOS
./mvnw clean verify
```

### Frontend Build & Lint
```bash
cd frontend/ledgerguard-web
npm install
npm run lint
npm run build
```

---

## 10. Documentation Links

- **Architecture Documentation:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- **Master Development Plan (Phases 0–44):** [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md)
- **Domain Model:** [docs/DOMAIN_MODEL.md](docs/DOMAIN_MODEL.md)
- **Failure Model & Mitigation:** [docs/FAILURE_MODEL.md](docs/FAILURE_MODEL.md)
- **Security Architecture:** [docs/SECURITY.md](docs/SECURITY.md)
- **Testing Strategy:** [docs/TESTING.md](docs/TESTING.md)
- **API Surface Plan:** [docs/API.md](docs/API.md)
- **Architecture Decision Records (ADRs):** [docs/adr/](docs/adr/)
