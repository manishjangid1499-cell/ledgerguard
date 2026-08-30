# LedgerGuard Project Status

## 1. Project Information
- **Project Name:** LedgerGuard — Payment Integrity & Ledger Platform
- **Current Phase:** Awaiting Phase 3
- **Status:** Phase 2 Complete (Verified & Hardened)
- **Completed Phases:**
  - **Phase 0 — Project Constitution, Architecture & Build Plan** (Completed: 2026-08-30)
  - **Phase 1 — Workspace Bootstrap & Multi-Module Setup** (Completed: 2026-08-30)
  - **Phase 2 — PostgreSQL, Kafka and Docker Local Development Infrastructure** (Completed: 2026-08-30)
- **Current Work:** PostgreSQL 17.11 with 3 isolated logical databases/roles and Apache Kafka 4.3.1 KRaft broker operational and functionally verified via internal and external listeners. Ready for API foundation.
- **Next Phase:** Phase 3 — LedgerGuard API foundation, profiles, health checks and standardized errors
- **Last Verified:** 2026-08-30
- **Git Branch:** `feat/phase-02-infrastructure` / `main` (workspace uncommitted)

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
  - **Host Listener:** `EXTERNAL://localhost:29092` (Functionally verified via Kafka console producer/consumer)
  - **Container Listener:** `PLAINTEXT://kafka:9092` (Functionally verified via internal produce/consume)
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
| **Phase 3** | LedgerGuard API Foundation & Observability | Planned | — |
| **Phase 4** | Identity, Authentication & Security | Planned | — |
| **Phase 5** | Frontend Shell & Authentication UI | Planned | — |
| **Phase 6** | Money Value Object & Ledger Schema | Planned | — |
| **Phase 7** | Atomic Balanced Journal Posting Engine | Planned | — |
| **Phase 8** | Wallet Balance Snapshots & Reconstruction | Planned | — |
| **Phase 9** | Idempotency Infrastructure | Planned | — |
| **Phase 10** | Atomic Internal Transfers | Planned | — |
| **Phase 11** | Concurrency Control & Deterministic Locking | Planned | — |
| **Phase 12** | Wallet, Transfer & Ledger Frontend Experience | Planned | — |
| **Phase 13** | Merchant Payments Domain | Planned | — |
| **Phase 14** | Full & Partial Refunds | Planned | — |
| **Phase 15** | Balance Holds & Available Balance Model | Planned | — |
| **Phase 16** | Transactional Outbox Persistence | Planned | — |
| **Phase 17** | Kafka Outbox Publisher & Event Contracts | Planned | — |
| **Phase 18** | Notification Worker & Idempotent Consumer | Planned | — |
| **Phase 19** | PSP & Banking Simulator | Planned | — |
| **Phase 20** | External Wallet Funding (Top-Ups) | Planned | — |
| **Phase 21** | External Payouts (Withdrawals) | Planned | — |
| **Phase 22** | PSP Webhook Signatures & Ordering | Planned | — |
| **Phase 23** | External State Machines & Ambiguity Handling | Planned | — |
| **Phase 24** | Core Reconciliation Engine | Planned | — |
| **Phase 25** | Reconciliation Recovery & Manual Review | Planned | — |
| **Phase 26** | Resilient Provider Client (Circuit Breakers) | Planned | — |
| **Phase 27** | Rate Limiting & Bounded Backpressure | Planned | — |
| **Phase 28** | Audit Trail & Security Hardening | Planned | — |
| **Phase 29** | Business & Integrity Metrics (Prometheus) | Planned | — |
| **Phase 30** | OpenTelemetry Tracing & Correlation IDs | Planned | — |
| **Phase 31** | Grafana Operations Dashboards | Planned | — |
| **Phase 32** | Money Integrity Failure Lab Backend | Planned | — |
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
- **Deviations Recorded:** None. Architecture strictly adheres to the locked specification.

---

## 5. Known Issues & Limitations
- **Kafka Single-Broker Development Limitation:** The Phase 2 Kafka setup runs a single local broker in KRaft mode for local development. It does not demonstrate multi-broker quorum replication or high-availability failover.

---

## 6. Verification Commands
- `docker compose config`: Validates Compose service configuration.
- `docker compose ps`: Confirms healthy state of PostgreSQL 17.11 (`ledgerguard-postgres`) and Kafka 4.3.1 (`ledgerguard-kafka`).
- `.\mvnw.cmd clean verify` (or `mvn clean verify`): Builds root reactor and all 4 backend modules; executes all unit and context load tests.
- `npm run lint` & `npm run build` (in `frontend/ledgerguard-web`): Type-checks and builds production bundle.
