# LedgerGuard Project Status

## 1. Project Information
- **Project Name:** LedgerGuard — Payment Integrity & Ledger Platform
- **Current Phase:** Phase 44 — v1.0.0 Portfolio Release
- **Status:** PHASE 44: COMPLETE — v1.0.0 PORTFOLIO RELEASE
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
  - **Phase 33 — Failure Lab Frontend & Interactive Invariant Visualizer** (Completed: 2026-09-06)
  - **Phase 34 — Complete Testcontainers & End-to-End Suite** (Completed: 2026-09-07)
  - **Phase 35 — Production Multi-Stage Docker Images & Compose** (Completed: 2026-09-07)
  - **Phase 36 — Nginx Production Reverse Proxy & SSL Configuration** (Completed: 2026-09-08)
  - **Phase 37 — GitHub Actions CI Pipeline** (Completed: 2026-09-08)
  - **Phase 38 — Financial Failure Scenarios in CI** (Completed: 2026-09-09)
  - **Phase 39 — Concurrency Contention & Performance Analysis** (Completed: 2026-09-10)
  - **Phase 40 — Backup, Restore & Operational Runbooks** (Completed: 2026-09-11)
  - **Phase 41 — Final Project Documentation, Architecture Diagrams & API Docs** (Completed: 2026-09-12)
  - **Phase 42 — Dead-Code, Dependency & Security Cleanup** (Completed: 2026-09-13)
  - **Phase 43 — Complete Release Verification** (Completed: 2026-09-13)
  - **Phase 44 — v1.0.0 Portfolio Release** (Completed: 2026-09-13)
- **Phase Completion:** 45 / 45 phases completed (100%)
- **Current Work:** Phase 44 v1.0.0 Portfolio Release prepared and completed:
  - Prepared initial v1.0.0 portfolio release artifacts, establishing authoritative release history in `CHANGELOG.md` adhering to Keep a Changelog standards.
  - Preserved authoritative Phase 43 release candidate verification metrics:
    - Backend regression: full `.\mvnw.cmd clean verify` passed with 760/760 tests (API: 675, PSP: 18, Notification Worker: 22, Failure Lab: 34, E2E: 11; 0 failures, 0 errors, 0 skipped).
    - Financial invariants: 0 invariant violations across double-entry ledger postings, snapshot evaluations, hold allocations, and failure recovery.
    - Frontend code quality: `npm run lint` passed with 0 errors and 0 warnings; `npm run build` completed successfully (exit code 0).
    - Frontend security: `npm audit` verified with 0 vulnerabilities (Critical: 0, High: 0, Moderate: 0, Low: 0).
    - Backend security: OWASP Dependency-Check verified with 0 CRITICAL vulnerabilities and exactly 2 known upstream HIGH vulnerabilities (`CVE-2026-54399` and `CVE-2026-54428` in shaded `httpcore5:5.3.6` within test dependency `docker-java-transport-zerodep:3.7.1`), which remain intentionally unsuppressed as documented upstream exceptions.
    - Dependency convergence: `.\mvnw.cmd enforcer:enforce "-Denforcer.rules=dependencyConvergence"` passed across all 6 reactor modules with 0 conflicts (`testcontainers.version = 2.0.5`, `tomcat.version = 11.0.25`).
    - Flyway migration integrity: verified complete sequential migration inventory across all database owners (`ledgerguard-api`: 17 migrations V1..V17; `psp-simulator`: 1 migration V1; `notification-worker`: 1 migration V1; 0 duplicates, 0 invalid naming).
    - Docker Compose validation: development (`docker-compose.yml`) and production (`docker-compose.prod.yml`) configurations validated with exit code 0.
    - Container health verification: started local development infrastructure stack; verified `postgres` (healthy), `kafka` (healthy), `prometheus` (healthy / HTTP 200), and `grafana` (healthy / HTTP 200); cleanly shut down without data loss.
    - UI / E2E workflow verification: verified comprehensive coverage across 11 end-to-end integration tests spanning platform startup, authentication & wallet creation, external funding top-ups, atomic peer-to-peer transfers, merchant payments & refunds, external payouts & holds, and asynchronous Kafka messaging notifications.
    - Documentation and API contract consistency: all 22 tracked markdown files verified with 0 broken local links; OpenAPI 3.1.0 specification verified with 22 operations and 4 security schemes.
    - Release candidate verification: PASS.
  - Finalized repository documentation, architectural diagrams, runbooks, benchmarks, and API specifications.
  - Release tag `v1.0.0` is designated as the final tagging target upon merge to `main`.
  - Documented upstream shaded dependency exception remains documented and unchanged in Section 5.
- **Next Phase:** None — Project Complete (v1.0.0 Portfolio Release)
- **Last Verified:** 2026-09-13
- **Git Branch:** release/phase-44-v1.0.0-portfolio-release


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
| **Phase 33** | Failure Lab Frontend & Visualizer | **Completed** | 2026-09-06 |
| **Phase 34** | Complete Testcontainers & E2E Suite | **Completed** | 2026-09-07 |
| **Phase 35** | Production Docker Images & Compose | **Completed** | 2026-09-07 |
| **Phase 36** | Nginx Reverse Proxy & Edge Routing | **Completed** | 2026-09-08 |
| **Phase 37** | GitHub Actions CI Pipeline | **Completed** | 2026-09-08 |
| **Phase 38** | Financial Failure Scenarios in CI | **Completed** | 2026-09-09 |
| **Phase 39** | Concurrency Contention & Performance | **Completed (Local Verified)** | 2026-09-10 |
| **Phase 40** | Backup, Restore & Operational Runbooks | **Completed (Local Verified)** | 2026-09-11 |
| **Phase 41** | Final Portfolio Documentation & API Docs | **Completed** | 2026-09-12 |
| **Phase 42** | Dead-Code & Security Cleanup | **Completed (Local Verified)** | 2026-09-13 |
| **Phase 43** | Complete Release Verification | **Completed** | 2026-09-13 |
| **Phase 44** | v1.0.0 Portfolio Release | **Completed** | 2026-09-13 |

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
- **Upstream Shaded Dependency Security Exception (CVE-2026-54399 & CVE-2026-54428):** Testcontainers 2.0.5 transitively brings in `com.github.docker-java:docker-java-transport-zerodep:3.7.1`, which shades `org.apache.httpcomponents.core5:httpcore5:5.3.6`. This shaded library carries two unpatched upstream HIGH CVEs (`CVE-2026-54399` and `CVE-2026-54428`). These findings are test-scoped only, do not affect production artifacts, and remain intentionally unsuppressed as a documented, accepted upstream dependency exception pending an upstream release by `docker-java`.

---

## 6. Verification Commands
- `docker compose config`: Validates Compose service configuration.
- `docker compose ps`: Confirms healthy state of PostgreSQL 17.11 (`ledgerguard-postgres`) and Kafka 4.3.1 (`ledgerguard-kafka`).
- `.\mvnw.cmd clean verify` (or `mvn clean verify`): Builds root reactor and all backend modules; executes all unit and context load tests.
- `npm run lint` & `npm run build` (in `frontend/ledgerguard-web`): Type-checks and builds production bundle.
