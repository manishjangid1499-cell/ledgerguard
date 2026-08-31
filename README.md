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
- **Concurrency Control**: Deterministic account lock ordering (lower identifier first) to prevent opposing-transfer circular-wait deadlocks and prevent double-spending under concurrent workloads.
- **Authoritative Idempotency**: Atomic database-backed request deduplication keys with cryptographic payload fingerprinting.
- **Transactional Outbox & Inbox**: Multi-worker `SKIP LOCKED` event publishing to Kafka with idempotent consumer processing.
- **Three-Level Reconciliation**: Journal invariant verification, snapshot vs. ledger verification, and internal ledger vs. external PSP state matching.
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

- **Current State:** Phase 9 Completed — PostgreSQL-Backed Idempotency Infrastructure: Core coordination service (`IdempotencyService`), Flyway V4 migration (`idempotency_records`), scoped uniqueness `(actor_user_id, operation, idempotency_key)`, deterministic SHA-256 request fingerprints, atomic slot claiming via `INSERT ... ON CONFLICT DO NOTHING`, pessimistic row-level coordination, same-transaction execution under `@Transactional REQUIRED`, fail-safe rollback of uncommitted claims, and database trigger-enforced immutability on completed records.
- **Next Step:** Phase 10 — Atomic Internal Transfers.
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
