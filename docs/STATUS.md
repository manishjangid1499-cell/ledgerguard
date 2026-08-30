# LedgerGuard Project Status

## 1. Project Information
- **Project Name:** LedgerGuard — Payment Integrity & Ledger Platform
- **Current Phase:** Awaiting Phase 1
- **Status:** Phase 0 Complete (Verified)
- **Completed Phases:**
  - **Phase 0 — Project Constitution, Architecture & Build Plan** (Completed: 2026-08-30)
- **Current Work:** Architecture, project constitution, failure models, security policies, build plan, and ADRs established. Ready for workspace bootstrap.
- **Next Phase:** Phase 1 — Repository/Maven/Java/frontend workspace bootstrap
- **Last Verified:** 2026-08-30
- **Git Branch:** Initial workspace (uninitialized / recommended `docs/phase-00-architecture`)

---

## 2. Phase Execution Matrix

| Phase | Title | Status | Date Completed |
| :--- | :--- | :--- | :--- |
| **Phase 0** | Project Constitution, Architecture & Build Plan | **Completed** | 2026-08-30 |
| **Phase 1** | Workspace Bootstrap & Multi-Module Setup | Planned | — |
| **Phase 2** | Docker Infrastructure & Database Baseline | Planned | — |
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

## 3. Architecture Deviations & Changes
- **Deviations Recorded:** None. Architecture strictly follows the locked modular monolith specification with PostgreSQL authoritative store, transactional outbox, and post-commit Kafka messaging.

---

## 4. Known Issues & Limitations
- Workspace contains documentation baseline. No executable code or build toolchain is present in Phase 0.

---

## 5. Verification Commands
- `docs-check`: Markdown structure, internal link integrity, and architectural consistency verified across all documentation and ADRs.
