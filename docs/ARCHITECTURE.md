# LedgerGuard Architecture Specification

## 1. Goals & Non-Goals

### Goals
- **Correctness First**: Ensure financial invariants hold under all operating conditions (no lost funds, no unbacked balances, no ghost debits/credits).
- **Immutable Double-Entry Ledger**: Every financial event maps to balanced debit and credit entries with immutable journal history.
- **ACID Transactional Consistency**: Localize multi-account balance checks, debit/credit creation, hold adjustments, and outbox insertion inside a single PostgreSQL transaction.
- **Concurrency Resilience**: Guarantee safety under concurrent requests and opposing transfers using deterministic row locking.
- **Authoritative Idempotency**: Provide atomic request deduplication at the database boundary to guarantee at-most-once execution for financial operations.
- **Asynchronous Reliability**: Publish domain events reliably using the Transactional Outbox pattern with `FOR UPDATE SKIP LOCKED` and process via idempotent consumers.
- **Ambiguity-Aware Integration**: Handle external Payment Service Provider (PSP) and banking timeouts, duplicate webhooks, and out-of-order deliveries through explicit state machines and reconciliation.
- **Automated Verification**: Prove money conservation via the Money Integrity Failure Lab.

### Non-Goals
- **Not a Real-Money Gateway**: The platform is an educational and portfolio demonstration system; it will not integrate with live banking rails or live merchant APIs.
- **No Foreign Exchange (FX) Conversion**: Demonstration operations execute within a single currency context (defaulting to INR in minor units / paise).
- **No Distributed Transactions (2PC/Sagas) for Core Ledgering**: Core money movement is intentionally unified inside a modular monolith to preserve local ACID guarantees.
- **No Early Sharding or Microservice Splitting**: Architectural simplicity is prioritized over premature horizontal partitioning.

---

## 2. System Architecture Diagram

```mermaid
flowchart TD
    subgraph Client_Layer["Client & Ingress Layer"]
        Browser["React Web Frontend\n(TypeScript / Vite / Material UI)"]
        Nginx["Nginx Reverse Proxy\n(HTTPS, Routing, Static Assets)"]
    end

    subgraph Application_Core["LedgerGuard Modular Monolith (ledgerguard-api)"]
        API_GW["REST API Controllers\n(Spring MVC / Validation / Security)"]
        
        subgraph Financial_Modules["Domain Modules"]
            IdentityMod["Identity & Access\n(JWT / RBAC)"]
            IdempotencyMod["Idempotency Engine\n(Request Fingerprints)"]
            AccountMod["Account & Balance Management\n(Snapshots & Holds)"]
            LedgerMod["Double-Entry Ledger Engine\n(Balanced Journal Posting)"]
            TransferMod["Transfer & Payment Service\n(Deterministic Locking)"]
            OutboxMod["Transactional Outbox\n(Skip Locked Poller)"]
            ReconMod["Reconciliation Engine\n(Internal & External)"]
        end
    end

    subgraph Persistence_Layer["Authoritative Storage"]
        PG_DB[("PostgreSQL\n(Financial Ledger, Snapshots,\nIdempotency, Outbox)")]
    end

    subgraph Messaging_Layer["Asynchronous Event Spine"]
        KafkaBrokers["Apache Kafka\n(Domain Topics:\nTransfers, Payments, Refunds)"]
    end

    subgraph Worker_Layer["Async Consumers & External World"]
        NotifWorker["Notification Worker\n(Inbox Deduplication / DLT)"]
        PSP_Sim["PSP Simulator\n(External Provider & Separate DB)"]
    end

    subgraph Observability_Layer["Observability & Reliability"]
        Prometheus["Prometheus / Micrometer"]
        Grafana["Grafana Dashboards"]
        OTel["OpenTelemetry Tracing / Jaeger"]
    end

    Browser -->|HTTPS / REST| Nginx
    Nginx -->|Proxy Pass| API_GW
    
    API_GW --> IdentityMod
    API_GW --> IdempotencyMod
    IdempotencyMod --> TransferMod
    TransferMod --> AccountMod
    TransferMod --> LedgerMod
    TransferMod --> OutboxMod

    IdentityMod --- PG_DB
    IdempotencyMod --- PG_DB
    AccountMod --- PG_DB
    LedgerMod --- PG_DB
    OutboxMod --- PG_DB

    OutboxMod -->|Publish Committed Events| KafkaBrokers
    KafkaBrokers -->|Consume with Inbox| NotifWorker

    TransferMod -->|External Deposit / Payout| PSP_Sim
    PSP_Sim -->|Webhooks / Callbacks| API_GW

    API_GW -.-> OTel
    PG_DB -.-> Prometheus
    API_GW -.-> Prometheus
    KafkaBrokers -.-> Prometheus
    Prometheus --> Grafana
```

---

## 3. Main Deployables

1. **`ledgerguard-api`**:
   - Primary Spring Boot modular monolith.
   - Contains all financial boundaries: Ledger, Accounts, Transfers, Payments, Refunds, Holds, Outbox, Reconciliation, Identity, and Audit.
   - Authoritative for PostgreSQL transactions.

2. **`psp-simulator`**:
   - Independent Spring Boot service with its own dedicated PostgreSQL instance.
   - Simulates external banking behavior: variable network latency, pre-processing failures, post-commit dropouts, duplicate webhooks, out-of-order callbacks, and manual capture states.

3. **`notification-worker`**:
   - Standalone background consumer application listening to Kafka topics.
   - Implements consumer-side inbox pattern for idempotency, exponential backoff retries, and dead-letter topic (DLT) routing.

4. **`ledgerguard-web`**:
   - Single-Page Application (SPA) built with React 18, TypeScript, Vite, TanStack Query, and Material UI.
   - Features customer portals (wallet balance, transfers, payment checkout, ledger drilldown) and operational consoles (system health, outbox monitoring, reconciliation viewer, Failure Lab runner).

5. **`failure-lab`**:
   - Standalone resilience test framework and execution harness.
   - Injects orchestrated chaos (concurrent race conditions, network cuts, worker kills, corrupted balance snapshot injections) and validates financial invariants.

---

## 4. PostgreSQL Financial Authority

- **Single Source of Truth**: PostgreSQL is the authoritative system of record for all account balances, journal entries, holds, and state transitions. Immutable POSTED journal transactions and entries represent the sole authoritative financial history.
- **No JPA Schema Mutations in Production**: Flyway migrations strictly manage schema evolutions; Hibernate is configured with `ddl-auto=validate`.
- **ACID Transaction Boundary**: Every transfer, financial posting, or wallet provisioning event executes inside a single database transaction with appropriate isolation levels (Read Committed / Serializable where required).
- **Derived Balance Snapshots**: The `ledger_balance_snapshots` table acts as a read-optimized, transactionally maintained derived projection (updated atomically via PostgreSQL database triggers on `DRAFT -> POSTED` transition), fully reconstructible and verifiable against the append-only `journal_entries` table.
- **Wallet Projection**: Wallets are application-facing domain projections over owned `ledger_accounts` and their corresponding `ledger_balance_snapshots` without a redundant persistent `wallets` table.
- **PostgreSQL-Backed Idempotency**: Financial write operations coordinate via `idempotency_records` using unique scope `(actor_user_id, operation, idempotency_key)` and deterministic SHA-256 request fingerprints. First callers atomically claim slots via `INSERT ... ON CONFLICT DO NOTHING` inside `@Transactional REQUIRED` boundaries, committing slot completion alongside financial mutations in a single atomic database transaction. Completed records are trigger-enforced immutable.
- **Deterministic Pessimistic Row Locking & Overdraft Prevention**: Internal transfers and merchant payments acquire pessimistic write locks (`SELECT ... FOR UPDATE` via `PESSIMISTIC_WRITE`) on all participating `ledger_balance_snapshots` rows strictly in global `ORDER BY ledger_account_id ASC`. Sufficient funds is validated against the locked customer row inside the transaction before invoking `LedgerPostingService`. Insufficient funds throws `InsufficientFundsException` (HTTP 409 `INSUFFICIENT_FUNDS`), rolling back the transaction and leaving the idempotency key unpoisoned for future retries after funding. Generic `LedgerPostingService` remains a generic accounting primitive allowing debits, while domain services (`TransferService`, `PaymentService`) enforce overdraft prevention. No JVM locks or distributed locks are used.
- **Merchant Payments Domain Architecture**: Customer-to-merchant commercial transactions represent synchronous internal payments orchestrated by `PaymentService`. Payments map to immutable `Payment` records with explicit lifecycle state machine (`CREATED -> PROCESSING -> SUCCEEDED / FAILED`), backed by multi-line balanced double-entry journal transactions (`DEBIT customer gross`, `CREDIT merchant net`, `CREDIT platform_fees fee`). Platform fee is calculated via integer arithmetic at 100 basis points with floor rounding (zero floating point). Up to 3 snapshot rows (customer, merchant, platform fee) are locked in a single deterministic query. Successful payments link to the `POSTED` journal transaction, update snapshots, and complete the idempotency slot (`merchant-payment:v1`) in one single ACID transaction.
- **Payment Refund Domain Architecture (Phase 14)**: Full and partial refunds are executed synchronously by `RefundService`. Refunds produce immutable `Refund` business records and new compensating double-entry journal transactions (`CREDIT customer refundAmount`, `DEBIT merchant merchantDebitAmount`, `DEBIT platform_fees feeDebitAmount`). The original `Payment` and original `journal_transaction` remain strictly immutable and in `SUCCEEDED`/`POSTED` status. Concurrency control locks the parent `Payment` row `FOR UPDATE` before evaluating cumulative refunds; cumulative cap $\sum \text{Refunds} \le \text{grossAmountMinor}$ is enforced by both application logic and PostgreSQL trigger. Telescoping pro-rata fee reversal (`original-payment-pro-rata:v1`) computes exact integer allocations without rounding drift. Original platform fee account is resolved directly from the original payment journal. Participating snapshot rows (customer, merchant if debit > 0, fee account if fee debit > 0) are locked in global `ORDER BY ledger_account_id ASC FOR UPDATE`. The entire refund commits atomically with the idempotency slot (`payment-refund:v1`) in one single ACID transaction.
- **Balance Holds & Available-Balance Architecture (Phase 15)**: Implements temporary fund reservations (`balance_holds`) that separate immutable posted ledger history from spendable capacity without altering double-entry journals or snapshots. Hold capacity is enforced at the database trigger level (`V8__create_balance_holds.sql`) and application layer by locking the parent snapshot row `FOR UPDATE` and asserting $\sum \text{ACTIVE holds} + \text{newAmount} \le \text{postedBalance}$. Available balance is derived on-the-fly ($\text{availableBalance} = \text{postedBalance} - \sum \text{ACTIVE holds}$) and never stored in a separate table. Spending operations (`TransferService`, `PaymentService`) lock snapshots and validate against Available Balance. Hold lifecycle transitions (`ACTIVE -> CONSUMED`, `ACTIVE -> RELEASED`, `ACTIVE -> EXPIRED`) are strictly controlled and terminal states are immutable. Multi-instance safe background expiration (`HoldExpirationService`) transitions due holds with `expires_at <= now` to `EXPIRED`. Refund operations continue to debit merchant balances directly, allowing valid negative available balances when liabilities exceed unreserved funds.
- **Transactional Outbox Persistence Architecture (Phase 16)**: Implements the transactional outbox persistence foundation (`outbox_events` table created in `V9__create_outbox_events.sql`) to eliminate the dual-write problem. When a financial business operation completes successfully, its domain event intent (`TRANSFER_COMPLETED`, `PAYMENT_SUCCEEDED`, `REFUND_COMPLETED`) is appended to `outbox_events` within the *exact same PostgreSQL database transaction* via `OutboxService` (`Propagation.MANDATORY`). If the business transaction rolls back for any reason, the outbox event rolls back atomically. Direct insert of `PUBLISHED` events is prohibited by database trigger; events start in `PENDING` status with `published_at NULL`. Event content and identity fields are immutable, and `PENDING` events cannot be deleted. Outbox rows are indexed via partial index `idx_outbox_events_pending_created` on `(created_at, id) WHERE status = 'PENDING'`. Outbox events represent integration/delivery intent and are not event sourcing or the financial source of truth. Phase 16 persists events only; Kafka publishing is strictly deferred to Phase 17.

---

## 5. Modular Monolith Financial Core

To eliminate the hazards of distributed two-phase commits across microservice boundaries, the core financial modules reside inside a single deployable (`ledgerguard-api`):
- Modules communicate via explicit, strongly-typed internal Java domain interfaces and application services.
- Data structures are encapsulated per module.
- Package structures follow a feature-first approach (`account`, `ledger`, `transfer`, `payment`, `outbox`, etc.) divided into `api`, `application`, `domain`, and `infrastructure` layers.

---

## 6. External PSP Boundary & Asynchronous Kafka Architecture

- **Separation of External Calls**: External PSP HTTP calls are never executed while holding open a database row lock or active transaction.
- **Outbox Pattern**: Changes to financial state and outbox event records are committed together in one atomic PostgreSQL transaction.
- **Outbox Publisher**: An asynchronous poller claims pending outbox events using `SELECT ... FOR UPDATE SKIP LOCKED` and publishes them to Apache Kafka.
- **Consumer Deduplication**: The `notification-worker` consumes domain events with database-backed inbox deduplication to guarantee safe duplicate delivery handling.

---

## 7. External PSP & Banking Simulator Architecture (Phase 19)

- **Standalone Service Boundary**: `psp-simulator` is an independent Spring Boot application with its own dedicated PostgreSQL database (`psp_simulator`) and Flyway migration stream (`V1__create_provider_simulator_tables.sql`).
- **Zero Direct LedgerGuard State Mutation**: The simulator does not access or mutate LedgerGuard financial state, accounts, journals, holds, or outbox tables.
- **Provider Operation Persistence**: Authoritative provider operation state is stored in `provider_operations` with unique `client_operation_id`, exact integer minor units (`amount_minor BIGINT`), strict enum constraints (`CREDIT`, `DEBIT`), and minimal status model (`SUCCEEDED`).
- **Atomic Idempotency & Conflict Semantics**: First-time operation requests claim slots via `INSERT INTO provider_operations ... ON CONFLICT (client_operation_id) DO NOTHING`. Replay with identical parameters returns `200 OK` and the existing operation. Conflicting replays with altered amount, currency, or operation type are rejected with `409 Conflict`.
- **In-Memory Scenario Control Plane**: Test scenarios are injected per-`clientOperationId` via `PUT /api/simulator/scenarios/{clientOperationId}` into a thread-safe `ScenarioRegistry` (cleared upon successful scenario execution or application restart).
- **Deterministic Fault Injection Modes**:
  - `NORMAL_SUCCESS`: Operation persists as `SUCCEEDED`, returns `201 Created`, schedules 1 webhook at `now()`.
  - `TIMEOUT_AFTER_SUCCESS`: Operation commits as `SUCCEEDED` to the database *before* a deliberate post-commit controller delay causes client-side timeout. Status recovery endpoint confirms durable success.
  - `DELAYED_WEBHOOK`: Operation returns `201 Created` immediately; webhook delivery is scheduled for `now() + delayMs`.
  - `DUPLICATE_WEBHOOK`: Exactly 1 logical event ID is generated, stored across 2 delivery rows (`delivery_number` 1 and 2), and dispatched twice to test downstream idempotency.
  - `TEMPORARY_500`: Returns HTTP 500 for $N$ configured attempts without creating database records. Subsequent retry succeeds normally.
- **Asynchronous Webhook Dispatcher**: Background `@Scheduled` worker claims due webhooks via `SELECT ... FROM provider_webhooks WHERE status = 'SCHEDULED' AND scheduled_at <= :now ORDER BY scheduled_at, id FOR UPDATE SKIP LOCKED LIMIT :batchSize` and dispatches them via Spring `RestClient`. Webhook delivery failures update webhook status to `FAILED` without affecting provider operation success.

---

## 8. Reconciliation Architecture

LedgerGuard defines a three-tier reconciliation architecture:
1. **Journal Invariant Reconciliation**: Scans all posted journal transactions to ensure $\sum \text{Debits} = \sum \text{Credits}$ across the entire ledger.
2. **Balance Snapshot Reconciliation**: Recalculates historical balances by summing immutable journal entries from the beginning of time (or latest audited checkpoint) and asserts identity with `account_balances`.
3. **External Provider Reconciliation**: Ingests external settlement reports from the PSP simulator and reconciles them against internal `payments`, `funding_operations`, and `payouts` to resolve `UNKNOWN` or pending transactions.

---

## 8. Observability Architecture

- **Metrics**: Micrometer instruments application metrics exposed to Prometheus (unbalanced transaction count, outbox queue lag, duplicate idempotency keys, state transition rejections, lock wait durations).
- **Dashboards**: Grafana visualizes financial integrity KPIs, system throughput, and queue health.
- **Distributed Tracing**: OpenTelemetry traces request lifecycles from HTTP ingress through database transactions, outbox publication, Kafka brokering, and notification worker consumption via correlation IDs.
- **Structured Logging**: JSON-formatted logs containing correlation IDs, tenant/client IDs, and operation IDs without logging sensitive financial credentials.

---

## 9. Horizontal Scaling & Deferred Sharding

- **Stateless Monolith Instances**: Multiple instances of `ledgerguard-api` can run concurrently behind the Nginx reverse proxy.
- **Database-Enforced Safety**: Concurrency control relies on PostgreSQL row locks (ordered by account ID) and unique database constraints, never on single-JVM locks (`synchronized` / `ReentrantLock`).
- **Deferred Sharding**: Database sharding is intentionally deferred (ADR-011) until scale necessitates cross-partition settlement mechanisms.

---

## 10. Explicitly Excluded Technologies

To maintain focus on correctness and avoid resume-driven architecture, the following are strictly excluded:
- Kubernetes, Service Mesh, Eureka, Spring Cloud Gateway
- MongoDB, Cassandra, Elasticsearch, RabbitMQ
- GraphQL, gRPC, Keycloak, Spark, Flink
- Blockchain, Distributed SAGAs across artificial microservices
- Full Event Sourcing frameworks (the ledger itself is the event log)
- Redis used merely for transient caching or non-authoritative locking

---

---

## 11. Frontend Financial Experience Architecture

- **Server-Authoritative State**: No client-side optimistic balance calculations or optimistic transfer history insertions. All balance and transaction states are invalidated and refetched from server read endpoints upon confirmed mutation success.
- **Financial Precision Invariants**:
  - Minor-unit amounts and balances are serialized as decimal JSON strings (`"10000"`), preventing precision truncation from JavaScript 64-bit float conversions.
  - Client-side INR human inputs are parsed into minor units using exact string decomposition and `BigInt` (no `parseFloat`, `Math.round`, or floating multiplication).
  - Minor units are formatted back to display INR using `BigInt` and Indian numbering notation (`₹1,23,456.78`).
- **Idempotency Lifecycle in Browser**:
  - Client generates `Idempotency-Key` via `crypto.randomUUID()`.
  - For ambiguous network errors or timeouts on unchanged destination/amount payloads, the form reuses the same idempotency key to prevent double-charging.
  - Any edit to destination or amount invalidates the key and assigns a new key.
  - Confirmed mutation results reset the form and allocate a fresh key.
- **Double-Submit Prevention & Mutation Configuration**:
  - React mutations disable submit buttons with `isPending` spinners and configure `retry: false` to avoid silent automatic retries.

---

## 12. Architectural Invariants

1. **Balance Equation**: $\text{Available Balance} = \text{Posted Balance} - \text{Active Holds}$.
2. **Double-Entry Balance**: For every transaction $T$, $\sum_{e \in T} \text{Debit}(e) = \sum_{e \in T} \text{Credit}(e)$.
3. **Immutability**: Once written, rows in `journal_transactions` and `journal_entries` cannot be updated or deleted.
4. **Deterministic Lock Ordering**: When locking multiple accounts, acquire locks in ascending lexicographical or numerical order of account IDs to prevent circular-wait deadlocks between opposing transfers.
5. **No Floating Point**: All monetary values are represented as `Money(Currency, long minorUnits)` on backend, and decimal strings / `BigInt` on frontend.
