# Changelog

All notable changes to the **LedgerGuard** platform will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-09-13

Initial portfolio release of the **LedgerGuard** Payment Integrity & Ledger Platform. This release culminates a 45-phase development lifecycle establishing a production-grade, correctness-first financial engine designed to eliminate double-spending, data drift, distributed ambiguity, and dual-write inconsistencies.

### Architecture & Foundations
- **Modular Multi-Module Reactor**: Clean Java 21 LTS Maven multi-module architecture separating domain concerns:
  - `backend/ledgerguard-api`: Authoritative double-entry core, payment processing, idempotency filters, and RESTful APIs.
  - `backend/psp-simulator`: Dedicated payment service provider simulator mimicking external banking partner behaviors.
  - `backend/notification-worker`: Asynchronous, non-web consumer processing Kafka domain events via an idempotent inbox.
  - `backend/failure-lab`: Controlled chaos harness executing financial invariant corruption scenarios against live services.
  - `backend/e2e-tests`: Multi-container End-to-End integration suite orchestrating real services via Testcontainers.
- **Frontend Architecture**: Modern Single-Page Application (`frontend/ledgerguard-web`) built with React 19, TypeScript 5.7, Vite 8, and Material UI 9, featuring role-aware dashboards and live invariant inspection.
- **Containerized Infrastructure Baseline**: Docker Compose orchestration for local development and multi-stage production deployment, provisioning PostgreSQL 17.11 (Alpine), Apache Kafka 4.3.1 (KRaft mode, zero ZooKeeper), Prometheus v3.2.1, Grafana 11.5.2, and Nginx edge reverse proxy with TLS termination.
- **Flyway Database Migrations**: 19 versioned migration scripts across modules (17 in API, 1 in PSP Simulator, 1 in Notification Worker) establishing schema definitions, indexes, and immutability triggers.

### Financial Integrity Core
- **Immutable Double-Entry Engine**: Every monetary transaction enforces exact debit-credit parity ($\sum \text{Debits} = \sum \text{Credits}$) in integer minor units (paise in INR currency).
- **PostgreSQL Immutability Triggers**: Engine-level database triggers (`trg_journal_transactions_immutability`, `trg_journal_entries_immutability`, and `trg_journal_transactions_balance_check`) preventing mutations or deletions of posted financial entries.
- **Derived Balance Snapshots**: Ledger accounts maintain point-in-time balance snapshots derived strictly from the immutable journal history, complete with forensic reconstruction services.
- **Deterministic Concurrency Control**: Consistent row-level lock acquisition ordering (`ORDER BY ledger_account_id ASC`) during transactional operations, eliminating circular-wait deadlocks under heavy concurrent load.
- **Overdraft & Race Prevention**: Strict balance assertions preventing accounts from negative overdraft balances during concurrent withdrawal attempts.
- **Cryptographic Request Idempotency**: Required `Idempotency-Key` request deduplication powered by SHA-256 payload fingerprinting and atomic database constraints.
- **Two-Phase Hold Lifecycle**: Balance reservations via balance holds (`ACTIVE`, `CAPTURED`, `RELEASED`) protecting funds during multi-step payments and external settlements without premature debiting.

### Payments & PSP Workflows
- **Peer-to-Peer Internal Transfers**: Atomic wallet-to-wallet transfers executing debit/credit postings in a single database transaction.
- **Merchant Checkout & Refunds**: Commercial payment flows with support for full and pro-rata partial fee refunds.
- **External Top-Ups (Funding)**: Asynchronous wallet funding top-up lifecycle with HMAC-SHA256 signature verification and replay prevention.
- **External Payouts (Withdrawals)**: Safe external fund disbursements utilizing balance hold reservations before external provider dispatch.
- **PSP Banking Simulator**: Standalone simulator modeling deterministic success, transient timeouts, network latency, and ambiguous failure outcomes.
- **Provider Outcome Handling (`UNKNOWN != FAILED`)**: Strict state machine architecture treating gateway timeouts and HTTP 500 errors as `UNKNOWN` rather than `FAILED`, preventing premature duplicate fund disbursements.

### Messaging & Reliability
- **Transactional Outbox Pattern**: Atomic database persistence of business events (`outbox_events`) alongside domain entity mutations, eliminating the dual-write vulnerability between database and message bus.
- **Asynchronous Kafka Delivery**: Background publisher relaying committed outbox events to Kafka topics with delivery retries and trace context propagation.
- **Idempotent Inbox Consumer**: Worker consumption tracking (`inbox_events`) guaranteeing exact-once processing semantics regardless of message redelivery.
- **Resilient Provider Client**: Resilience4j circuit breakers, bounded exponential retries, and rate limiting with backpressure guards.

### Reconciliation & Correctness
- **Core Reconciliation Engine**: Multi-level reconciliation comparing internal journal transactions, balance snapshot parity, and external provider statements.
- **Reconciliation Recovery Workflows**: Automated case management, variance detection, forensic auditing, and operator-assisted snapshot repair endpoints.
- **Continuous Invariant Assertions**: Database-enforced and application-level financial invariant evaluations ensuring ledger zero-sum integrity across all accounts.

### Resilience & Observability
- **Money Integrity Failure Lab**: Comprehensive test suite injecting 34 distinct failure modes (split-brain race conditions, simulated power failures, network timeouts, duplicate webhooks, corrupted snapshot data) to verify self-healing and invariant preservation.
- **Metrics & Monitoring**: Prometheus operational, integrity, and business metric exporters tracking balance drift, outbox lag, and circuit breaker trip rates.
- **Grafana Dashboards**: Provisioned financial integrity and operations dashboards visualizing transaction throughput, error distributions, and system health.
- **Distributed Tracing & Structured Logging**: OpenTelemetry correlation IDs and W3C Trace Context propagating across API, worker, Kafka headers, and database transactions.

### Testing & Release Verification
Authoritative verification baseline validated during release candidate qualification:
- **Backend Test Suite**: **760 / 760 PASS** (`BUILD SUCCESS`)
  - `backend/ledgerguard-api`: 675 tests
  - `backend/psp-simulator`: 18 tests
  - `backend/notification-worker`: 22 tests
  - `backend/failure-lab`: 34 tests
  - `backend/e2e-tests`: 11 tests
- **Failures / Errors / Skipped**: **0 Failures, 0 Errors, 0 Skipped**
- **Financial Invariant Violations**: **0 Violations**
- **Frontend Code Quality**: `npm run lint` &rarr; **PASS** (0 errors, 0 warnings)
- **Frontend Production Bundle**: `npm run build` &rarr; **PASS** (Vite production bundle compiled cleanly)
- **Frontend Dependencies**: `npm audit` &rarr; **0 vulnerabilities**
- **Maven Dependency Convergence**: `.\mvnw.cmd enforcer:enforce` &rarr; **PASS** across all 6 reactor modules (Testcontainers `2.0.5`, Tomcat `11.0.25`)
- **Docker Compose Configurations**: `docker-compose.yml` (dev) and `docker-compose.prod.yml` (prod) syntax and semantic validation &rarr; **PASS** (exit code 0)
- **Container Health Probes**: Live local development stack startup and probe validation &rarr; **PASS** (PostgreSQL healthy, Kafka healthy, Prometheus healthy, Grafana healthy)
- **Database Migrations**: 19 Flyway migrations verified in sequential order across all database-owning modules
- **Documentation Link Integrity**: 22 tracked Markdown files checked with 0 broken internal links
- **OpenAPI Specification**: OpenAPI 3.1.0 specification validated with 22 operations and 4 security schemes

### Security
- **Identity & Access Management**: Role-Based Access Control (`CUSTOMER`, `MERCHANT`, `OPS`) with BCrypt password hashing, short-lived JWT access tokens, and HTTP-only refresh cookies.
- **Security Hardening**: Content Security Policy (CSP), HTTP Strict Transport Security (HSTS), and control character input validation.
- **OWASP Dependency-Check Status**:
  - **CRITICAL Vulnerabilities**: **0**
  - **Documented Upstream HIGH Vulnerabilities**: **2**
    - `CVE-2026-54399`: HTTP/2 HPACK decompression resource exhaustion in shaded `org.apache.httpcomponents.core5:httpcore5:5.3.6`
    - `CVE-2026-54428`: HTTP/2 rapid reset stream handling in shaded `org.apache.httpcomponents.core5:httpcore5:5.3.6`
    - **Affected Component**: Test-scoped `com.github.docker-java:docker-java-transport-zerodep:3.7.1` (transitive dependency of Testcontainers 2.0.5).
    - **Resolution Status**: Intentionally **UNSUPPRESSED** and recorded as a documented upstream dependency exception pending an upstream patch by `docker-java`.

### Documentation & Portfolio
- Authoritative system architecture with embedded Mermaid diagrams in `docs/ARCHITECTURE.md`.
- Complete domain models and invariants catalog in `docs/DOMAIN_MODEL.md`.
- Comprehensive failure and recovery matrix in `docs/FAILURE_MODEL.md`.
- OpenAPI 3.1.0 specification in `docs/openapi.json` and API endpoint guide in `docs/API.md`.
- Operational disaster recovery runbooks and database backup/restore scripts in `docs/RUNBOOKS.md`.
- Concurrency contention benchmarks and connection pool profiling in `docs/BENCHMARKS.md`.
- Comprehensive portfolio overview and mathematical invariant guarantees in `README.md`.
