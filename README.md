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
1. **`ledgerguard-api`**: Core modular monolith managing ledger accounts, double-entry journal transactions, transfers, payments, holds, outbox events, and reconciliation.
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

- **Immutable Double-Entry Accounting**: Balanced debit/credit entries; posted transactions are permanent and corrected only through compensating entries.
- **Concurrency Control**: Deterministic account lock ordering (lower identifier first) to prevent opposing-transfer circular-wait deadlocks and prevent double-spending under concurrent workloads.
- **Authoritative Idempotency**: Atomic database-backed request deduplication keys with cryptographic payload fingerprinting.
- **Transactional Outbox & Inbox**: Multi-worker `SKIP LOCKED` event publishing to Kafka with idempotent consumer processing.
- **Three-Level Reconciliation**: Journal invariant verification, snapshot vs. ledger verification, and internal ledger vs. external PSP state matching.
- **Observability**: Micrometer metrics, Prometheus, Grafana dashboards, OpenTelemetry distributed tracing, and structured logging.

---

## 6. Technology Direction

- **Backend:** Java 21, Spring Boot 3.x, Spring Data JPA / Hibernate, Spring Security, Flyway, Maven.
- **Authoritative Store:** PostgreSQL (`ddl-auto=validate`).
- **Asynchronous Messaging:** Apache Kafka.
- **Frontend:** React, TypeScript, Vite, Material UI, TanStack Query, React Hook Form.
- **Testing:** JUnit 5, Testcontainers (PostgreSQL, Kafka), Mockito, ArchUnit.
- **Infrastructure:** Docker, Docker Compose, Nginx, Prometheus, Grafana, OpenTelemetry.

---

## 7. Current Project Status

- **Current State:** Phase 0 Completed — Architecture Definition, Project Constitution, and Development Roadmap established.
- **Next Step:** Phase 1 — Repository, Maven, Java 21, and Frontend Workspace Bootstrap.
- **Roadmap:** Detailed progress is tracked in [docs/STATUS.md](docs/STATUS.md).

---

## 8. Documentation Links

- **Architecture Documentation:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- **Master Development Plan (Phases 0–44):** [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md)
- **Domain Model:** [docs/DOMAIN_MODEL.md](docs/DOMAIN_MODEL.md)
- **Failure Model & Mitigation:** [docs/FAILURE_MODEL.md](docs/FAILURE_MODEL.md)
- **Security Architecture:** [docs/SECURITY.md](docs/SECURITY.md)
- **Testing Strategy:** [docs/TESTING.md](docs/TESTING.md)
- **API Surface Plan:** [docs/API.md](docs/API.md)
- **Architecture Decision Records (ADRs):** [docs/adr/](docs/adr/)
