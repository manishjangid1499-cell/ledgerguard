# LedgerGuard Project Status

## 1. Project Information
- **Project Name:** LedgerGuard — Payment Integrity & Ledger Platform
- **Current Phase:** Phase 32 Complete (Verified)
- **Status:** Phase 32 Complete (Verified)
- **Completed Phases:**
  - **Phase 0 — Project Constitution, Architecture & Build Plan** (Completed: 2026-08-30)
  - **Phase 1 — Workspace Bootstrap & Multi-Module Setup** (Completed: 2026-08-30)
  - **Phase 2 — PostgreSQL, Kafka and Docker Local Development Infrastructure** (Completed: 2026-08-30)
  - **Phase 3 — LedgerGuard API foundation, profiles, health checks and standardized errors** (Completed: 2026-08-31)
  - **Phase 4 — Identity, Authentication, Authorization, JWT and Refresh Tokens** (Completed: 2026-08-31)
  - **Phase 5 — Frontend Shell, Authentication UI, Routing & Secure API Integration** (Completed: 2026-08-31)
  - **Phase 6 — Money Value Object, Ledger Accounts & Immutable Journal Persistence** (Completed: 2026-08-31)
  - **Phase 7 — Atomic Double-Entry Posting Engine** (Completed: 2026-08-31)
  - **Phase 8 — Wallets & Derived Balance Snapshots** (Completed: 2026-08-31)
  - **Phase 9 — PostgreSQL-Backed Idempotency Infrastructure** (Completed: 2026-08-31)
  - **Phase 10 — Atomic Internal Transfers** (Completed: 2026-08-31)
  - **Phase 11 — Concurrency Control, Deterministic Locking & Overdraft Prevention** (Completed: 2026-08-31)
  - **Phase 12 — Wallet, Transfer & Ledger Frontend Experience** (Completed: 2026-08-31)
  - **Phase 13 — Merchant Payments Domain** (Completed: 2026-09-01)
  - **Phase 14 — Full & Partial Payment Refunds** (Completed: 2026-09-01)
  - **Phase 15 — Balance Holds & Available-Balance Model** (Completed: 2026-09-01)
  - **Phase 16 — Transactional Outbox Persistence** (Completed: 2026-09-01)
  - **Phase 17 — Kafka Outbox Publisher & Event Contracts** (Completed: 2026-09-01)
  - **Phase 18 — Notification Worker & Idempotent Inbox Consumer** (Completed: 2026-09-01)
  - **Phase 19 — External PSP & Banking Simulator** (Completed: 2026-09-01)
  - **Phase 20 — External Wallet Funding / Top-Ups** (Completed: 2026-09-01)
  - **Phase 21 — External Payouts & Balance Holds** (Completed: 2026-09-02)
  - **Phase 22 — PSP Webhook Signatures, Deduplication & Ordering** (Completed: 2026-09-02)
  - **Phase 23 — External State Machines & Ambiguous Outcomes** (Completed: 2026-09-02)
  - **Phase 24 — Core Reconciliation Engine** (Completed: 2026-09-04)
  - **Phase 25 — Reconciliation Recovery & Manual Review** (Completed: 2026-09-04)
  - **Phase 26 — Resilient Provider Client (Circuit Breaker & Retries)** (Completed: 2026-09-05)
  - **Phase 27 — Rate Limiting & Bounded Backpressure** (Completed: 2026-09-05)
  - **Phase 28 — Audit Trail & Security Hardening** (Completed: 2026-09-05)
  - **Phase 29 — Business & Integrity Metrics (Prometheus)** (Completed: 2026-09-05)
  - **Phase 30 — OpenTelemetry Tracing & Correlation IDs** (Completed: 2026-09-05)
  - **Phase 31 — Grafana Operations & Financial Integrity Dashboards** (Completed: 2026-09-05)
  - **Phase 32 — Money Integrity Failure Lab Backend** (Completed: 2026-09-06)
- **Current Work:** Phase 32 completed. Built the Money Integrity Failure Lab execution engine in `backend/failure-lab`:
  - Decoupled Real-System Architecture (Model C): `src/main` contains generic runner, independent SQL financial invariant oracle (`FinancialInvariantOracle`), fail-closed safety guard with positive authorization (`EnvironmentGuard`, `LabDatabaseTarget`), snapshot fault injector (`SnapshotFaultInjector`), scenario models and interfaces. `src/test` contains real LedgerGuard production system integration test harness booting `@SpringBootTest(classes = LedgerGuardApplication.class)` with test-scoped dependency on `ledgerguard-api`.
  - Fail-Closed Safety Guard (`EnvironmentGuard`): Deny-by-default positive authorization model requiring local target confirmation (`LabDatabaseTarget`) and rejecting production/staging hostnames, non-local IP addresses (e.g. `10.0.0.8`), and forbidden keywords.
  - Snapshot Fault Injector (`SnapshotFaultInjector`): Parameterized narrow mutation affecting only target snapshot balance row under valid `LabDatabaseTarget`.
  - Independent SQL Oracle (`FinancialInvariantOracle`): Executes direct SQL queries against database asserting structural journal integrity, debit/credit zero-sum balance, snapshot reconstruction parity from `POSTED` journals under V3 normal balance rules, scoped non-negative available balances, internal transfer conservation, and exactly-once settlement journal counts.
  - Concurrency Lock & Runner (`ScenarioRunner`, `ConcurrencyGuard`, `ScenarioRegistry`): Enforces max 1 concurrent scenario execution via bounded semaphore, 30s timeout safety, structured timeline events, and in-memory bounded execution history (100 runs).
  - 4 Real Production Chaos Scenarios:
    1. `OPPOSING_TRANSFERS`: Concurrent A->B and B->A transfers via real `TransferService.createTransfer` under `CyclicBarrier(2)` validating deterministic `ORDER BY ledger_account_id ASC` deadlock-free locking, idempotency replay, and money conservation.
    2. `TIMEOUT_AFTER_COMMIT`: Real `PayoutService.requestPayout` under external provider transport timeout (`HttpProviderTestAdapter.Mode.TIMEOUT_AFTER_SUCCESS`); verifies `UNKNOWN != FAILED`, hold remains `ACTIVE`, and real `ProviderStatusPollingService` status recovery settles to `SUCCEEDED` with exactly-once journal posting.
    3. `CORRUPTED_SNAPSHOT`: Deliberately mutates `ledger_balance_snapshots.balance_minor` via `SnapshotFaultInjector` under `LabDatabaseTarget`; real Phase 24 `SnapshotConsistencyChecker` detects `SNAPSHOT_MISMATCH`, and real Phase 25 `SnapshotAutoRepairService` auto-repairs snapshot from immutable `POSTED` journals.
    4. `WEBHOOK_RACE`: 5 concurrent duplicate HMAC-SHA256 signed webhooks dispatched to real `ProviderWebhookController.receiveWebhook`; verifies real HMAC validation, provider event deduplication on `(provider_id, provider_event_id)`, and strictly single settlement journal posting.
  - Test Suite: 15 tests in `backend/failure-lab` (6 test classes: `EnvironmentGuardTest` [9 tests], `OpposingTransfersScenarioTest` [1 test], `TimeoutAfterCommitScenarioTest` [1 test], `CorruptedSnapshotScenarioTest` [1 test], `WebhookRaceScenarioTest` [1 test], `FailureLabSuiteRunnerTest` [1 test]) validating individual real scenarios, suite execution, environment safety, and concurrency bounds.
  - Zero-Impact Invariant: Zero production Java code changes (`ledgerguard-api/src/main/java` 0 lines modified), zero database migrations (V1-V17 frozen, V18 absent). Total workspace tests: 729 tests (675 API, 17 PSP, 22 Notification Worker, 15 Failure Lab; 0 failures, 0 errors, 0 skipped).
- **Next Phase:** Phase 33 — Failure Lab Frontend & Visualizer
- **Last Verified:** 2026-09-06
- **Git Branch:** feat/phase-32-money-integrity-failure-lab

---

## 2. Toolchain & Infrastructure Verified
- **Docker Engine:** 29.6.2 (build dfc4efb)
- **Docker Compose:** v5.3.1
- **PostgreSQL Container:** `postgres:17.11-alpine` (PostgreSQL 17.11 runtime)
  - **Host Port:** `5432`
  - **Volume:** `ledgerguard-postgres-data`
  - **Databases & Enforced Ownership:**
    - `ledgerguard` (Owner: `ledgerguard_app`, used by `ledgerguard-api`)
    - `psp_simulator` (Owner: `psp_simulator_app`, used by `psp-simulator`)
    - `notification_worker` (Owner: `notification_worker_app`, used by `notification-worker`)
  - **Database Connection Isolation:** `PUBLIC` connect revoked; cross-database connection attempts denied at engine level.
- **Kafka Container:** `apache/kafka:4.3.1` (Apache Kafka 4.3.1 in KRaft mode, No ZooKeeper)
  - **Mode:** KRaft (Broker ID: 1, Controller ID: 1, Cluster ID configured)
  - **Host Listener:** `EXTERNAL://localhost:29092`
  - **Container Listener:** `PLAINTEXT://kafka:9092`
  - **Volume:** `ledgerguard-kafka-data`
  - **Topic Auto-Creation:** Disabled (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`) for deterministic development.
  - **Single Broker Note:** Local development topology; does not provide production HA or multi-broker replication.
- **Java:** 21.0.2 LTS (Oracle Corporation, 64-Bit Server VM)
- **javac:** 21.0.2
- **Maven:** 3.9.16 (and Maven Wrapper 3.9.16)
- **Spring Boot:** 4.1.1
- **Node.js:** v24.19.0 (Node 24 LTS)
- **npm:** 11.17.0
- **TypeScript:** 5.7.3
- **Vite:** 8.1.5 (resolved 8.2.2)
- **React:** 19.0.0 (resolved 19.2.8)
- **Material UI:** 9.4.0
- **Git:** 2.55.0.windows.3

---

## 3. Phase Execution Matrix

| Phase | Title | Status | Date Completed |
| :--- | :--- | :--- | :--- |
| **Phase 0** | Project Constitution, Architecture & Build Plan | **Completed** | 2026-08-30 |
| **Phase 1** | Workspace Bootstrap & Multi-Module Setup | **Completed** | 2026-08-30 |
| **Phase 2** | Docker Infrastructure & Database Baseline | **Completed** | 2026-08-30 |
| **Phase 3** | LedgerGuard API Foundation & Observability | **Completed** | 2026-08-31 |
| **Phase 4** | Identity, Authentication & Security | **Completed** | 2026-08-31 |
| **Phase 5** | Frontend Shell & Authentication UI | **Completed** | 2026-08-31 |
| **Phase 6** | Money Value Object & Ledger Schema | **Completed** | 2026-08-31 |
| **Phase 7** | Atomic Balanced Journal Posting Engine | **Completed** | 2026-08-31 |
| **Phase 8** | Wallet Balance Snapshots & Reconstruction | **Completed** | 2026-08-31 |
| **Phase 9** | Idempotency Infrastructure | **Completed** | 2026-08-31 |
| **Phase 10** | Atomic Internal Transfers | **Completed** | 2026-08-31 |
| **Phase 11** | Concurrency Control, Deterministic Locking & Overdraft Prevention | **Completed** | 2026-08-31 |
| **Phase 12** | Wallet, Transfer & Ledger Frontend Experience | **Completed** | 2026-08-31 |
| **Phase 13** | Merchant Payments Domain | **Completed** | 2026-09-01 |
| **Phase 14** | Full & Partial Refunds | **Completed** | 2026-09-01 |
| **Phase 15** | Balance Holds & Available Balance Model | **Completed** | 2026-09-01 |
| **Phase 16** | Transactional Outbox Persistence | **Completed** | 2026-09-01 |
| **Phase 17** | Kafka Outbox Publisher & Event Contracts | **Completed** | 2026-09-01 |
| **Phase 18** | Notification Worker & Idempotent Consumer | **Completed** | 2026-09-01 |
| **Phase 19** | PSP & Banking Simulator | **Completed** | 2026-09-01 |
| **Phase 20** | External Wallet Funding (Top-Ups) | **Completed** | 2026-09-01 |
| **Phase 21** | External Payouts (Withdrawals) | **Completed** | 2026-09-02 |
| **Phase 22** | PSP Webhook Signatures & Ordering | **Completed** | 2026-09-02 |
| **Phase 23** | External State Machines & Ambiguity Handling | **Completed** | 2026-09-02 |
| **Phase 24** | Core Reconciliation Engine | **Completed** | 2026-09-04 |
| **Phase 25** | Reconciliation Recovery & Manual Review | **Completed** | 2026-09-04 |
| **Phase 26** | Resilient Provider Client (Circuit Breakers) | **Completed** | 2026-09-05 |
| **Phase 27** | Rate Limiting & Bounded Backpressure | **Completed** | 2026-09-05 |
| **Phase 28** | Audit Trail & Security Hardening | **Completed** | 2026-09-05 |
| **Phase 29** | Business & Integrity Metrics (Prometheus) | **Completed** | 2026-09-05 |
| **Phase 30** | OpenTelemetry Tracing & Correlation IDs | **Completed** | 2026-09-05 |
| **Phase 31** | Grafana Operations Dashboards | **Completed** | 2026-09-05 |
| **Phase 32** | Money Integrity Failure Lab Backend | **Completed** | 2026-09-06 |
| **Phase 33** | Failure Lab Frontend & Visualizer | Planned | — |
| **Phase 34** | Complete Testcontainers & E2E Suite | Planned | — |
| **Phase 35** | Production Docker Images & Compose | Planned | — |
| **Phase 36** | Nginx Reverse Proxy & Edge Routing | Planned | — |
| **Phase 37** | GitHub Actions CI Pipeline | Planned | — |
| **Phase 38** | Financial Failure Scenarios in CI | Planned | — |
| **Phase 39** | Concurrency Contention & Performance | Planned | — |
| **Phase 40** | Backup, Restore & Operational Runbooks | Planned | — |
| **Phase 41** | Final Portfolio Documentation & API Docs | Planned | — |
| **Phase 42** | Dead-Code & Security Cleanup | Planned | — |
| **Phase 43** | Release Verification | Planned | — |
| **Phase 44** | v1.0.0 Portfolio Release | Planned | — |

---

## 4. Architecture Deviations & Changes
- **Phase 28 Administrative Account Freeze / Unfreeze Scope Adjustment (Human-Approved Option A)**:
  - Phase 28 freeze/unfreeze was deferred by human architectural decision.
  - Current ACTIVE/DISABLED state is authentication status only. There is no administrative account-freeze workflow.
  - Existing access JWTs are not immediately revoked by user status changes; access JWT TTL remains approximately 15 minutes and tokens expire naturally.
  - No per-request user DB lookup was introduced because that would conflict with Phase 27 overload/backpressure guarantees.
  - Account freeze and unfreeze endpoints (`POST /api/ops/users/{id}/freeze`, `POST /api/ops/users/{id}/unfreeze`) and associated stateful JWT revocation mechanisms (token denylist, disabled-user cache, per-request DB lookup filter) were deferred from Phase 28 per human approval.
  - The Phase 28 scope focused strictly on database-enforced immutable operational audit logging (`audit_events` for reconciliation workflows), transactional audit atomicity, control character input hardening, security header hardening (explicit CSP and HSTS), and PII/secret logging auditing.

---

## 5. Known Issues & Limitations
- **Kafka Single-Broker Development Limitation:** The local Kafka setup runs a single local broker in KRaft mode for local development.

---

## 6. Verification Commands
- `docker compose config`: Validates Compose service configuration.
- `docker compose ps`: Confirms healthy state of PostgreSQL 17.11 (`ledgerguard-postgres`) and Kafka 4.3.1 (`ledgerguard-kafka`).
- `.\mvnw.cmd clean verify` (or `mvn clean verify`): Builds root reactor and all backend modules; executes all unit and context load tests.
- `npm run lint` & `npm run build` (in `frontend/ledgerguard-web`): Type-checks and builds production bundle.
