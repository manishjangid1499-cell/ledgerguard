# LedgerGuard Master Build Plan (Phases 0–44)

This master build plan details the 45 sequential development phases of **LedgerGuard — Payment Integrity & Ledger Platform**.
Each phase must satisfy its definition of done before advancing to the next.

---

### Phase 0: Project Constitution, Architecture & Build Plan
- **Purpose**: Record authoritative system architecture, domain invariants, failure models, security policies, and roadmap.
- **Deliverables**: `README.md`, `docs/ARCHITECTURE.md`, `docs/BUILD_PLAN.md`, `docs/STATUS.md`, `docs/DOMAIN_MODEL.md`, `docs/FAILURE_MODEL.md`, `docs/SECURITY.md`, `docs/TESTING.md`, `docs/API.md`, ADRs 001–011.
- **Validation**: Markdown consistency, absence of architectural contradictions, clean directory baseline.
- **Git Commit Message**: `docs: define LedgerGuard architecture and roadmap`

---

### Phase 1: Workspace Bootstrap & Multi-Module Build Setup
- **Purpose**: Initialize root Maven multi-module structure, Java 21 toolchain, Git configuration, and Vite React frontend workspace.
- **Deliverables**: Root `pom.xml`, `.gitignore`, `.editorconfig`, Maven wrappers, skeleton modules (`backend/ledgerguard-api`, `backend/psp-simulator`, `backend/notification-worker`, `frontend/ledgerguard-web`).
- **Validation**: Clean Maven compile across all modules; clean frontend `npm run build` or `npm run lint`.
- **Git Commit Message**: `chore: bootstrap multi-module maven and frontend workspace`

---

### Phase 2: Docker Development Infrastructure & Database Baseline
- **Purpose**: Configure containerized PostgreSQL, Kafka, and local orchestration for local development.
- **Deliverables**: `docker-compose.yml`, `.env.example`, PostgreSQL init scripts (dual database setup: `ledgerguard_db` and `psp_db`), Kafka & Zookeeper/Kraft broker setup.
- **Validation**: `docker compose up -d` boots healthy PostgreSQL and Kafka instances; connection verification scripts pass.
- **Git Commit Message**: `infra: configure docker compose for postgresql and kafka development`

---

### Phase 3: LedgerGuard API Foundation & Observability Baseline
- **Purpose**: Establish Spring Boot core foundation, profiles (`dev`, `test`, `prod`), structured RFC-7807 error handling, and Actuator health endpoints.
- **Deliverables**: `ledgerguard-api` main application class, application YAML configs, `GlobalExceptionHandler`, RFC-7807 `ProblemDetail` structures, logging configuration.
- **Validation**: Application boots successfully; `/actuator/health` returns `200 UP`; mock test endpoints return structured JSON errors.
- **Git Commit Message**: `feat(api): establish spring boot foundation and structured error handling`

---

### Phase 4: Identity, Authentication & Security Infrastructure
- **Purpose**: Implement user identity, role-based authorization (`CUSTOMER`, `MERCHANT`, `OPS`), password hashing (BCrypt), and JWT/refresh-token security.
- **Deliverables**: User entities, Flyway V1 migration (`users`, `refresh_tokens`), `JwtService`, `SecurityConfig`, auth endpoints (`/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`), token revocation.
- **Validation**: Integration tests with Testcontainers verifying authentication, password hashing, token expiration, and role-based endpoint protection.
- **Git Commit Message**: `feat(security): implement identity, jwt authentication and rbac authorization`

---

### Phase 5: Frontend Shell, Authentication UI & API Client
- **Purpose**: Create React frontend shell, authentication screens, JWT interceptor, responsive layout, and role-aware navigation.
- **Deliverables**: Vite/React structure, React Router routes, Material UI theme, Auth context, Login/Register forms, protected routes, Axios/TanStack Query client.
- **Validation**: Successful login/registration against `ledgerguard-api`, token refresh on 401, clean UI rendering and routing.
- **Git Commit Message**: `feat(web): build frontend authentication shell and routing infrastructure`

---

### Phase 6: Money Value Object & Double-Entry Ledger Schema
- **Purpose**: Implement exact financial `Money` value object and Flyway migrations for double-entry ledger accounts and journal persistence.
- **Deliverables**: `Money` class (Currency + `BIGINT` minor units / paise), Flyway migration for `ledger_accounts`, `journal_transactions`, `journal_entries`, JPA entity definitions.
- **Validation**: Unit tests for `Money` arithmetic (preventing rounding errors, overflow checks), Flyway migration validation on PostgreSQL.
- **Git Commit Message**: `feat(ledger): implement money value object and double-entry schema`

---

### Phase 7: Atomic Balanced Journal Posting Engine
- **Purpose**: Implement the authoritative posting engine that enforces $\sum \text{Debits} = \sum \text{Credits}$ within atomic transactions.
- **Deliverables**: `JournalPostingEngine`, balance assertion logic, transaction creation service, immutability triggers/guards.
- **Validation**: Unit & integration tests asserting rejection of unbalanced postings, verification that posted entries cannot be mutated or deleted.
- **Git Commit Message**: `feat(ledger): implement atomic balanced journal posting engine`

---

### Phase 8: Wallet Accounts, Balance Snapshots & Reconstruction
- **Purpose**: Manage customer/merchant wallets, maintain `account_balances` snapshots, and implement balance reconstruction from the ledger.
- **Deliverables**: Wallet service, Flyway migration for `account_balances`, snapshot update listeners, `BalanceReconstructionService`.
- **Validation**: Integration tests verifying that snapshot values strictly match the cumulative sum of immutable ledger entries.
- **Git Commit Message**: `feat(account): implement wallet balance snapshots and ledger reconstruction`

---

### Phase 9: Idempotency Infrastructure
- **Purpose**: Implement atomic request deduplication with cryptographic request hashing and PostgreSQL uniqueness constraints.
- **Deliverables**: Flyway migration for `idempotency_records`, `@Idempotent` annotation, Spring interceptor/filter, request body caching filter, SHA-256 fingerprinting.
- **Validation**: Concurrency tests with identical `Idempotency-Key` returning identical cached responses; mismatched payload with same key returns 409 Conflict.
- **Git Commit Message**: `feat(idempotency): implement database-backed request deduplication and fingerprinting`

---

### Phase 10: Atomic Internal Transfers
- **Purpose**: Implement end-to-end peer-to-peer internal transfers with atomic debit/credit journal creation and snapshot updates.
- **Deliverables**: `TransferService`, `TransferController` (`POST /api/transfers`), Flyway migration for `transfers`, transfer validation logic.
- **Validation**: Testcontainers integration tests verifying that successful transfers credit the destination, debit the source, and preserve ledger balance.
- **Git Commit Message**: `feat(transfer): implement atomic peer-to-peer internal transfers`

---

### Phase 11: Concurrency Control & Deterministic Locking
- **Purpose**: Prevent opposing-transfer circular-wait deadlocks and handle race conditions during simultaneous transfers and concurrent withdrawal attempts.
- **Deliverables**: Deterministic account lock ordering algorithm (`ORDER BY account_id` during `PESSIMISTIC_WRITE`), overdraft prevention guards, transient database retry readiness.
- **Validation**: Multithreaded stress tests (`ExecutorService` / 50+ threads) verifying prevention of opposing-transfer deadlocks, zero negative balances, and zero lost updates.
- **Git Commit Message**: `feat(concurrency): implement deterministic account locking and race condition protection`

---

### Phase 12: Wallet, Transfer & Ledger Frontend Experience
- **Purpose**: Develop rich frontend views for wallet dashboards, peer transfers, and interactive double-entry ledger drilldowns.
- **Deliverables**: Customer Dashboard, Wallet Balance view, Transfer form with Idempotency Key generation, Journal Transaction inspector showing balanced debit/credit rows.
- **Validation**: End-to-end UI verification performing transfers, validating immediate ledger entry reflection, and handling client-side validation errors.
- **Git Commit Message**: `feat(web): build wallet dashboard, transfer ui and ledger inspector`

---

### Phase 13: Merchant Payments Domain
- **Purpose**: Implement specialized merchant checkout workflows with dedicated business lifecycle states and metadata.
- **Deliverables**: `PaymentService`, `PaymentController` (`POST /api/payments`), Flyway migration for `payments`, payment state machine (`CREATED`, `PROCESSING`, `SUCCEEDED`, `FAILED`).
- **Validation**: Tests verifying payment lifecycle transitions, customer wallet debits, merchant wallet credits, and fee allocation.
- **Git Commit Message**: `feat(payment): implement merchant payment workflows and state machines`

---

### Phase 14: Full & Partial Refunds
- **Purpose**: Implement immutable refund operations with cumulative refund cap constraints.
- **Deliverables**: `RefundService`, `RefundController` (`POST /api/payments/{id}/refund`), Flyway migration for `refunds`, compensating journal entry generation.
- **Validation**: Concurrency tests verifying that $\sum \text{Refunds} \le \text{Payment Amount}$; attempts to over-refund fail atomically.
- **Git Commit Message**: `feat(refund): implement full and partial payment refund engine`

---

### Phase 15: Balance Holds & Available-Balance Model
- **Purpose**: Introduce balance holds (`ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`) to separate posted balances from spendable available balances.
- **Deliverables**: `HoldService`, Flyway migration for `balance_holds`, hold expiration background task, available balance calculations.
- **Validation**: Tests verifying that active holds reduce available balance without altering posted balance until consumed or released.
- **Git Commit Message**: `feat(hold): implement balance hold lifecycle and available balance management`

---

### Phase 16: Transactional Outbox Persistence
- **Purpose**: Persist domain events atomically within the financial business database transaction.
- **Deliverables**: Flyway migration for `outbox_events`, `OutboxService`, event serialisation framework, outbox insertion interceptors.
- **Validation**: Integration tests proving that failed business transactions roll back outbox event rows, while committed transactions preserve them in `PENDING` state.
- **Git Commit Message**: `feat(outbox): implement transactional outbox event persistence`

---

### Phase 17: Kafka Outbox Publisher & Event Contracts
- **Purpose**: Publish committed outbox events to Kafka topics using `FOR UPDATE SKIP LOCKED` polling and versioned event envelopes.
- **Deliverables**: `OutboxPublisherWorker`, Kafka producer configuration, CloudEvents-compatible event envelopes, multi-worker lock safety.
- **Validation**: Integration tests with Kafka Testcontainers verifying that published outbox rows transition to `PUBLISHED` and messages land on topics.
- **Git Commit Message**: `feat(kafka): implement outbox publisher worker and event contracts`

---

### Phase 18: Notification Worker & Idempotent Inbox Consumer
- **Purpose**: Consume Kafka events in `notification-worker` with at-least-once resilience and database-backed deduplication.
- **Deliverables**: `notification-worker` application, Flyway migration for `processed_events` and `notification_deliveries`, Kafka listener, DLT error handler.
- **Validation**: Tests publishing duplicate Kafka messages and asserting that notifications are delivered exactly once without duplicate side effects.
- **Git Commit Message**: `feat(worker): implement notification worker with idempotent inbox deduplication`

---

### Phase 19: External PSP & Banking Simulator
- **Purpose**: Build an independent deployable simulating banking gateways and asynchronous network faults.
- **Deliverables**: `psp-simulator` Spring Boot app, separate PostgreSQL database schema (`provider_operations`, `provider_webhooks`), scenario injection API (`NORMAL_SUCCESS`, `TIMEOUT_AFTER_SUCCESS`, `DELAYED_WEBHOOK`, `DUPLICATE_WEBHOOK`, `TEMPORARY_500`).
- **Validation**: Integration tests verifying that the simulator generates deterministic error scenarios and latency profiles.
- **Git Commit Message**: `feat(psp): build external psp simulator with fault injection modes`

---

### Phase 20: External Wallet Funding (Top-Ups)
- **Purpose**: Implement asynchronous wallet funding via external PSP deposit requests and settlement workflows.
- **Deliverables**: `FundingService`, `FundingController` (`POST /api/funding`), PSP client, Flyway migration for `funding_operations`, clearing account ledger entries.
- **Validation**: Tests verifying that funding requests create clearing entries and credit user wallets only upon authoritative provider confirmation.
- **Git Commit Message**: `feat(funding): implement external wallet top-up and clearing workflow`

---

### Phase 21: External Payouts (Withdrawals)
- **Purpose**: Implement wallet withdrawals to external bank accounts using balance holds, payout lifecycles, and failure recovery.
- **Deliverables**: `PayoutService`, `PayoutController` (`POST /api/payouts`), Flyway migration for `payouts`, balance hold integration during in-flight payouts.
- **Validation**: Tests verifying that funds are held during payout processing, released on PSP failure, and consumed on PSP success.
- **Git Commit Message**: `feat(payout): implement external payout processing with balance hold protection`

---

### Phase 22: PSP Webhook Signatures, Deduplication & Ordering
- **Purpose**: Secure webhook endpoints against tampering, duplicate delivery, and out-of-order execution.
- **Deliverables**: HMAC-SHA256 signature verification, Flyway migration for `provider_events`, timestamp validation window, state machine ordering guards.
- **Validation**: Tests rejecting invalid signatures, ignoring duplicate webhooks, and queuing/correctly processing out-of-order status updates.
- **Git Commit Message**: `feat(webhook): implement psp webhook signature verification and deduplication`

---

### Phase 23: External State Machines & Ambiguous Outcomes
- **Purpose**: Handle lost network responses and provider timeouts without premature transaction failures.
- **Deliverables**: Explicit state machine (`CREATED`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`, `RECONCILIATION_REQUIRED`), background status poller.
- **Validation**: Simulation of `TIMEOUT_AFTER_SUCCESS` verifying that transaction enters `UNKNOWN` rather than `FAILED`, preventing duplicate money generation.
- **Git Commit Message**: `feat(lifecycle): implement ambiguous external outcome handling and status recovery`

---

### Phase 24: Core Reconciliation Engine
- **Purpose**: Implement automated three-level reconciliation (journal balance, snapshot consistency, provider settlement).
- **Deliverables**: `ReconciliationEngine`, Flyway migration for `reconciliation_runs` and `reconciliation_items`, daily/on-demand batch jobs.
- **Validation**: Tests detecting intentional simulated balance corruptions, un-cleared funding records, and PSP status discrepancies.
- **Git Commit Message**: `feat(reconciliation): implement three-level reconciliation engine`

---

### Phase 25: Reconciliation Recovery & Manual Review Workflows
- **Purpose**: Provide workflows to repair snapshot mismatches and investigate unresolvable external discrepancies.
- **Deliverables**: Discrepancy resolution service, `ReconciliationController` (`/api/reconciliation/*`), snapshot auto-repair utilities, manual-review queues.
- **Validation**: Verification that auto-repair successfully rebuilds corrupted snapshot balances from immutable journal entries.
- **Git Commit Message**: `feat(reconciliation): implement discrepancy recovery and manual review workflows`

---

### Phase 26: Resilient Provider Client (Circuit Breaker & Retries)
- **Purpose**: Protect `ledgerguard-api` from cascading external failures with Resilience4j circuit breakers, timeouts, and backoff.
- **Deliverables**: Resilience4j configurations, Feign/RestClient retry interceptors, fallback handlers, provider bulkhead configuration.
- **Validation**: Tests proving that downstream PSP downtime trips the circuit breaker and prevents thread pool exhaustion.
- **Git Commit Message**: `feat(resilience): implement circuit breakers, retries and provider bulkheads`

---

### Phase 27: Rate Limiting & Bounded Backpressure
- **Purpose**: Guard API endpoints against denial-of-service and unbounded queue growth.
- **Deliverables**: Token-bucket rate limiting filter, bounded thread execution pools, Kafka consumer backpressure configuration.
- **Validation**: Load tests verifying that excess requests receive HTTP 429 Too Many Requests without exhausting database connections.
- **Git Commit Message**: `feat(resilience): implement rate limiting and bounded thread backpressure`

---

### Phase 28: Audit Trail & Security Hardening
- **Purpose**: Log immutable administrative and operational actions; harden input sanitization and HTTP security headers.
- **Deliverables**: Flyway migration for `audit_events`, `AuditService`, Spring Security CSP/CORS/HSTS headers, PII masking in logs.
- **Validation**: Tests verifying that account freezes, unfreezes, and manual reconciliation actions write unalterable audit records.
- **Git Commit Message**: `feat(audit): implement administrative audit logging and security hardening`

---

### Phase 29: Business & Integrity Metrics (Prometheus)
- **Purpose**: Expose high-value financial integrity and operational metrics via Micrometer and Prometheus.
- **Deliverables**: Custom metrics (`unbalanced_journal_count`, `reconciliation_discrepancies`, `outbox_lag_seconds`, `duplicate_idempotency_keys`).
- **Validation**: Integration test scraping `/actuator/prometheus` and verifying that custom metrics reflect live financial transactions.
- **Git Commit Message**: `feat(metrics): expose custom financial integrity and outbox metrics`

---

### Phase 30: OpenTelemetry Distributed Tracing & Correlation IDs
- **Purpose**: Enable end-to-end request tracing across HTTP ingress, database transactions, outbox publication, Kafka, and worker processing.
- **Deliverables**: OpenTelemetry Java agent / Micrometer Tracing integration, correlation ID propagation headers (`X-Correlation-Id`), MDC structured logging.
- **Validation**: Tracing verification showing unified trace IDs across API request logs, Kafka event headers, and notification worker logs.
- **Git Commit Message**: `feat(tracing): implement distributed tracing with opentelemetry and correlation ids`

---

### Phase 31: Grafana Operations & Financial Integrity Dashboards
- **Purpose**: Provide production-grade Grafana dashboards for financial health, outbox queues, and system throughput.
- **Deliverables**: Grafana dashboard JSON definitions (`infrastructure/grafana/dashboards/`), Prometheus datasource provisioning.
- **Validation**: Verification of dashboard layout and metric visualisations against running Prometheus instances.
- **Git Commit Message**: `feat(observability): provision grafana dashboards for financial integrity and outbox`

---

### Phase 32: Money Integrity Failure Lab Backend
- **Purpose**: Build the programmatic chaos execution engine to simulate concurrent failure scenarios and assert invariants.
- **Deliverables**: `failure-lab` backend test engine, chaos scenario runners (opposing transfers, timeout after commit, corrupted snapshot, webhook races).
- **Validation**: Execution of automated failure suites validating that every scenario cleanly proves money conservation invariants.
- **Git Commit Message**: `feat(lab): implement money integrity failure lab test runner and chaos scenarios`

---

### Phase 33: Failure Lab Frontend & Interactive Invariant Visualizer
- **Purpose**: Create an interactive operations UI for triggering chaos scenarios and watching real-time invariant assertions.
- **Deliverables**: Failure Lab React view, live execution status indicators, mathematical invariant report cards, error injection controls.
- **Validation**: End-to-end browser walkthrough triggering chaos runs and viewing real-time invariant pass/fail telemetry.
- **Git Commit Message**: `feat(web): build failure lab operations console and invariant visualizer`

---

### Phase 34: Complete Testcontainers & End-to-End Suite
- **Purpose**: Unify all integration, database, messaging, and multi-service flows into a single reproducible Testcontainers test suite.
- **Deliverables**: Comprehensive E2E test suite running PostgreSQL and Kafka Testcontainers simultaneously.
- **Validation**: `mvn clean verify` runs all unit, integration, and E2E tests cleanly without external dependencies.
- **Git Commit Message**: `test(e2e): unify complete testcontainers integration and e2e test suite`

---

### Phase 35: Production Multi-Stage Docker Images & Compose
- **Purpose**: Create lightweight, secure, multi-stage Dockerfiles for all deployables and an all-in-one local compose stack.
- **Deliverables**: Dockerfiles for `ledgerguard-api`, `psp-simulator`, `notification-worker`, `ledgerguard-web`, root `docker-compose.prod.yml`.
- **Validation**: Clean `docker compose -f docker-compose.prod.yml up --build` boots the entire stack in production mode.
- **Git Commit Message**: `infra: create multi-stage production dockerfiles and compose stack`

---

### Phase 36: Nginx Production Reverse Proxy & SSL Configuration
- **Purpose**: Configure Nginx as the edge ingress gateway with SSL termination, API routing, static asset caching, and security headers.
- **Deliverables**: `infrastructure/nginx/nginx.conf`, gzip configuration, proxy pass upstream rules, rate limit zones.
- **Validation**: Verification that requests to `http://localhost/api/*` proxy to `ledgerguard-api` and `/` serves `ledgerguard-web`.
- **Git Commit Message**: `infra: configure nginx edge reverse proxy and static asset routing`

---

### Phase 37: GitHub Actions CI Pipeline
- **Purpose**: Automate continuous integration testing, code formatting, security linting, and Docker image builds.
- **Deliverables**: `.github/workflows/ci.yml`, `.github/workflows/dependabot.yml`, cache configurations for Maven and Node.
- **Validation**: CI workflow dry-run or local action validation verifying clean compilation and test execution.
- **Git Commit Message**: `ci: configure github actions pipeline for maven and react builds`

---

### Phase 38: Financial Failure Scenarios in CI
- **Purpose**: Run selected Money Integrity Failure Lab scenarios in GitHub Actions to prevent regression of financial invariants.
- **Deliverables**: CI chaos test profile, automated invariant reporting step in GitHub Actions.
- **Validation**: CI pipeline passes with all financial failure assertions executing and logging 0 invariant violations.
- **Git Commit Message**: `ci: integrate money integrity failure lab into continuous integration`

---

### Phase 39: Concurrency, Contention & Performance Analysis
- **Purpose**: Benchmark transaction throughput under high concurrency and profile database connection pool contention.
- **Deliverables**: Concurrency benchmark scripts, HikariCP pool tuning, performance report documentation in `docs/BENCHMARKS.md`.
- **Validation**: Benchmark run demonstrating consistent response times and resilient lock contention handling under saturated thread pools.
- **Git Commit Message**: `perf: analyze concurrency contention and optimize database connection pool`

---

### Phase 40: Backup, Restore & Operational Runbooks
- **Purpose**: Author operational procedures for disaster recovery, point-in-time PostgreSQL recovery, and Kafka lag remediation.
- **Deliverables**: `docs/RUNBOOKS.md`, database backup/restore scripts (`scripts/backup-db.sh`, `scripts/restore-db.sh`).
- **Validation**: Simulated database restore testing snapshot verification against ledger entries.
- **Git Commit Message**: `docs: author operational runbooks and disaster recovery procedures`

---

### Phase 41: Final Project Documentation, Architecture Diagrams & API Docs
- **Purpose**: Polish final repository documentation, OpenAPI/Swagger specifications, and portfolio presentation assets.
- **Deliverables**: Comprehensive `README.md`, updated architectural diagrams, Swagger UI OpenAPI JSON export.
- **Validation**: Complete documentation links check; verification that all diagrams and API specs render accurately.
- **Git Commit Message**: `docs: polish portfolio documentation, openapi specs and diagrams`

---

### Phase 42: Dead-Code, Dependency & Security Cleanup
- **Purpose**: Audit codebase for unused dependencies, stale imports, outdated packages, and security vulnerabilities.
- **Deliverables**: Maven dependency convergence check, OWASP dependency check / npm audit run, code cleanup.
- **Validation**: Zero high/critical vulnerabilities; zero unreferenced classes or dead configuration files.
- **Git Commit Message**: `refactor: clean dead code and resolve dependency vulnerabilities`

---

### Phase 43: Complete Release Verification
- **Purpose**: Execute an end-to-end release candidate smoke test across all deployables, databases, and UI workflows.
- **Deliverables**: Release checklist verification report, clean run across all unit/integration/E2E/chaos suites.
- **Validation**: 100% green test suite, all Docker containers healthy, zero invariant violations across full test matrix.
- **Git Commit Message**: `chore: execute complete release candidate verification`

---

### Phase 44: v1.0.0 Portfolio Release
- **Purpose**: Package final v1.0.0 release tags, release notes, and portfolio submission artifacts.
- **Deliverables**: Release notes (`CHANGELOG.md`), updated `STATUS.md` marking v1.0.0 release.
- **Validation**: Repository is pristine, fully documented, self-contained, and ready for portfolio evaluation.
- **Git Commit Message**: `release: v1.0.0 portfolio release of ledgerguard platform`
