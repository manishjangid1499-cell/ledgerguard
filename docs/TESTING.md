# LedgerGuard Testing Strategy & Invariant Verification

## 1. Testing Philosophy & Invariant Priority

In LedgerGuard, tests are not merely code-coverage metrics; they are executable mathematical proofs that the central invariant holds:

$$\text{\bf MONEY MUST NEVER BE CREATED, DESTROYED, DUPLICATED, OR SILENTLY LOST.}$$

To validate correctness under real financial conditions, tests must run against real PostgreSQL database instances and real Kafka message brokers. **In-memory databases like H2 are strictly prohibited** for concurrency and transaction testing because H2 does not replicate PostgreSQL's row-locking mechanics, MVCC transaction semantics, or `FOR UPDATE SKIP LOCKED` behavior.

---

## 2. Testing Pyramid & Layers

```
                       / \
                      /   \
                     / E2E \   (Full Multi-Service & Web UI)
                    /-------\
                   / Chaos & \  (Money Integrity Failure Lab)
                  /   Lab     \
                 /-------------\
                /  Integration  \ (PostgreSQL & Kafka Testcontainers)
               /   Concurrency   \
              /-------------------\
             /   Unit & Domain     \ (Pure Java Invariants, Money Math)
            /_______________________\
```

### Layer 1: Unit & Domain Invariant Tests
- **Scope**: Fast, isolated tests focusing on pure Java domain models.
- **Targets**:
  - `Money` value object precision, addition, subtraction, zero checks, overflow protections.
  - Journal transaction balance validation ($\sum \text{Debits} == \sum \text{Credits}$).
  - Double-entry debit/credit classification rules per account type.
  - State machine transitions (legal vs. illegal state transitions).

### Layer 2: Concurrency & Lock Contention Tests (Real PostgreSQL)
- **Scope**: Multi-threaded execution against real PostgreSQL instances via Testcontainers.
- **Targets**:
  - **Opposing Transfers**: Simultaneous transfers between Account A and Account B across concurrent threads (e.g. 20 threads) to verify elimination of opposing-transfer circular-wait deadlocks via deterministic `ORDER BY ledger_account_id ASC` row locking.
  - **Double-Spend Races**: 2 concurrent spends of 7,000 from 10,000 (1 success, 1 insufficient funds) and 10 concurrent requests of 3,000 from 10,000 (exactly 3 succeed, 7 fail).
  - **50+ Thread High Contention Stress Test**: 50 concurrent threads attempting transfers from an initial balance of 25,000 (1,000 each) proving exactly 25 succeed, 25 fail with `INSUFFICIENT_FUNDS`, and final balance is exactly 0.
  - **Reconstruction Verification**: Asserting that for every touched account in concurrency tests, `snapshot.balance_minor` matches exact sum of historical `journal_entries`.
  - **Key Unpoisoning / Retry After Funding**: Verifying that a transfer rejected for insufficient funds rolls back cleanly and permits the caller to retry the exact same `Idempotency-Key` and fingerprint once funds are deposited.
  - **Concurrent Refunds (Phase 14)**: Multi-threaded concurrent partial refund tests (e.g. 50 threads requesting 1,000 each against a 25,000 payment) verifying parent payment row lock `FOR UPDATE`, cumulative refund cap enforcement ($\sum \text{Refunds} \le \text{grossAmountMinor}$), exactly 25 successes / 25 failures with HTTP 409 `REFUND_LIMIT_EXCEEDED`, and full economic balance restoration.
  - **Concurrent Balance Holds & Cross-Operation Contention (Phase 15)**: Multi-threaded concurrent hold creation tests (e.g. 50 threads requesting 1,000 each against a 25,000 posted balance) verifying snapshot row lock `FOR UPDATE`, cumulative hold capacity trigger, exactly 25 successes / 25 failures with `InsufficientAvailableBalanceException`, and final held balance of 25,000. Cross-operation race tests (concurrent 7,000 Hold vs 7,000 Transfer on 10,000 balance) proving mutual exclusion where exactly one succeeds.

### Layer 3: Integration Tests (Testcontainers)
- **Scope**: Testing database repositories, Spring Data JPA mappings, and messaging pipelines.
- **Targets**:
  - **Balance Holds Immutability & Lifecycle (Phase 15)**: Direct JDBC tests verifying trigger rejection of direct non-`ACTIVE` inserts, mutations of immutable identity fields, invalid status transitions, and `DELETE` operations. Hold release, consumption, and multi-instance safe background expiration verified against live PostgreSQL Testcontainers instances.
  - **Idempotency Races**: Multi-threaded concurrent executions with identical `(actor_user_id, operation, idempotency_key, fingerprint)` verifying that exactly 1 underlying operation executes and duplicates receive replayed cached results; concurrent conflicting fingerprints reject losers without duplicate execution.
  - **Idempotency Immutability & Rollback**: Direct JDBC tests verifying trigger rejection of direct `COMPLETED` inserts, metadata updates, status reversals, and deletions; operation rollback cleanly rolls back uncommitted `IN_PROGRESS` claims allowing retry.
  - **Transactional Outbox Persistence (Phase 16)**: Validating that database rollbacks drop outbox rows, and committed transactions persist events in `PENDING` state with `published_at NULL`. Direct JDBC tests verifying trigger rejection of direct `PUBLISHED` inserts, non-object JSON payloads, content mutations, and deletion of `PENDING` rows. Explicit domain event emission verified for `TRANSFER_COMPLETED`, `PAYMENT_SUCCEEDED`, and `REFUND_COMPLETED`, asserting zero duplicate events on idempotency replay, zero events on failed financial operations, exact decimal string monetary serialization (safe above `MAX_SAFE_INTEGER`), and full business transaction rollback when outbox append fails.
  - **Kafka Outbox Publisher & Event Contracts (Phase 17)**: Bounded batch claiming via `SELECT ... FOR UPDATE SKIP LOCKED` verified with real Kafka Testcontainers. CloudEvents 1.0 structured envelope validation (`specversion: 1.0`, stable `id`, `occurred_at` timestamp, `aggregate_id` key, string-encoded monetary units), post-broker-ACK lifecycle transition to `PUBLISHED`, at-least-once crash window redelivery producing duplicate Kafka messages with identical event IDs, non-blocking disjoint multi-worker claiming, and rollback upon broker/send failure.
  - **Kafka Consumer Inbox**: Redelivery of duplicate Kafka messages asserts zero duplicate domain side-effects (Phase 18).

### Layer 4: Webhook, Financial API & Security Integration Tests
- **Scope**: HTTP layer security, role-based authorization, financial read endpoints, and signature validation.
- **Targets**:
  - Verification that unauthenticated requests return HTTP 401 across all financial and identity routes.
  - Verification that an `OPS` user is forbidden (HTTP 403) from accessing user wallets (`/api/wallets/me`) and transfer endpoints (`/api/transfers`).
  - Scoped wallet history and detail lookup: `GET /api/transfers` returns only actor-involved transfers (as source or destination); unrelated transfers are excluded.
  - Privacy preservation: `GET /api/transfers/{id}` returns HTTP 404 Not Found (not 403) for unrelated users, preventing disclosure of another user's transfer existence.
  - Double-entry journal inspector: verifies that transfer details expose immutable `POSTED` double-entry entries with balanced debits and credits matching the transfer amount.
  - Merchant Payments API (`POST /api/payments`): restricted to `CUSTOMER` role (403 for `MERCHANT` and `OPS`, 401 for unauthenticated); verifies 201 Created on new payment, 200 OK on idempotent replay, 400 on invalid payload or non-positive amount, 404 on missing/non-MERCHANT payee, and 409 on idempotency key payload conflict.
  - Payment Database Constraints (`PaymentDatabaseConstraintTest`): direct JDBC tests verifying trigger rejection of direct `SUCCEEDED`/`PROCESSING`/`FAILED` inserts, rejection of direct transitions to `SUCCEEDED` pointing to DRAFT or missing journals, immutability of terminal records, and delete rejection.
  - Serialization precision: verifies that minor-unit amounts and balances are serialized as decimal JSON strings, preserving precision even for values exceeding JavaScript `Number.MAX_SAFE_INTEGER` (`9,007,199,254,740,991`).
  - Validation of HMAC-SHA256 signatures on inbound PSP webhooks; rejection of tampered or expired payloads.

### Layer 5: Reconciliation Engine & Snapshot Invariant Tests
- **Scope**: Batch reconciliation and snapshot consistency validation.
- **Targets**:
  - Flyway V3 migration historical backfill reconstructs exact balances from immutable POSTED journals.
  - Concurrency tests verify multi-threaded postings on shared accounts yield zero lost updates and no deadlocks via deterministic row update ordering.
  - Balance snapshot arithmetic overflow triggers immediate PostgreSQL exception, aborting and rolling back the complete journal posting transaction.
  - Intentionally injected balance snapshot drift is flagged and auto-repaired from ledger entries.
  - Unbalanced transactions in test datasets trigger immediate system integrity alarms.
  - Discrepancies between internal payment states and external PSP settlement dumps are identified and routed to `MANUAL_REVIEW`.

### Layer 6: Money Integrity Failure Lab (Chaos & E2E)
- **Scope**: Automated system-wide chaos suite injecting severe adverse conditions:
  - Network disconnection after PostgreSQL commit before client response.
  - Outbox publisher killed mid-execution.
  - Simulated PSP timeout after provider-side transaction commit.
  - Out-of-order and duplicate webhook arrivals.
- **Verification Rule**: After each chaos scenario, the system executes an automated invariant audit asserting:
  $$\text{Unbalanced Transactions} = 0$$
  $$\text{Duplicate Economic Effects} = 0$$
  $$\text{Negative Available Balances} = 0$$
  $$\text{Snapshot Inconsistencies} = 0$$
  $$\sum \text{Current Balances} = \sum \text{Opening Balances} + \sum \text{Inflows} - \sum \text{Outflows}$$

---

## 3. Why Real PostgreSQL is Mandatory (No H2)

The following database-level behaviors are central to LedgerGuard's correctness and **cannot** be verified in H2:
1. **`SELECT ... FOR UPDATE` Row Locking**: H2 uses different locking granularities (often table-level locks or alternate MVCC lock queues) that do not reproduce PostgreSQL row lock contention or deadlock graphs.
2. **`FOR UPDATE SKIP LOCKED`**: PostgreSQL's non-blocking row claiming mechanism for multi-worker outbox processing is specific to PostgreSQL and MySQL 8+.
3. **Partial Indexes & Constraints**: Flyway migrations utilize PostgreSQL-specific constraints and index structures.
4. **Read Committed & Serializable Isolation**: PostgreSQL's snapshot isolation semantics differ significantly from in-memory substitutes.

---

## 4. PSP Simulator Testing Strategy (Phase 19)

- **Independent Flyway V1 & Schema Isolation**: `psp-simulator` integration tests run exclusively against real PostgreSQL Testcontainers instances (`psp_simulator_test` database), executing only PSP Flyway V1 migrations.
- **Database Constraint Verification (`PspDatabaseConstraintTest`)**:
  - `provider_operations`: Unique `client_operation_id`, positive `amount_minor`, strict `INR` currency constraint, valid enum checks (`CREDIT`, `DEBIT`, `SUCCEEDED`, scenarios), and enforced non-null `completed_at` on `SUCCEEDED`.
  - `provider_webhooks`: Foreign key enforcement to `provider_operations`, positive `delivery_number`, non-object JSON rejection, valid status enums, unique `(event_id, delivery_number)` constraint, and permission of duplicate `event_id` with distinct `delivery_number` (required for duplicate webhook testing).
- **HTTP Transport & Fault Verification (`PspSimulatorIntegrationTest`)**:
  - `@SpringBootTest(webEnvironment = RANDOM_PORT)` with Spring `RestClient`.
  - `NORMAL_SUCCESS`: Operation created (201 Created), 1 DB row, 1 webhook row, single webhook delivered with matching payload.
  - `IDEMPOTENT_REPLAY`: Replaying same request returns 200 OK, identical `providerOperationId`, 1 DB operation, no duplicate webhook generation.
  - `CONFLICTING_REPLAY`: Replaying `clientOperationId` with modified amount or conflicting `webhookUrl` returns HTTP 409 Conflict without modifying existing record.
  - `CONCURRENT_IDEMPOTENCY`: 20 concurrent threads with same `clientOperationId` yield exactly 1 DB operation and 1 webhook set; all threads receive successful responses with the identical operation ID.
  - `TEMPORARY_500`: Returns HTTP 500 for $N$ configured attempts with 0 database rows created; subsequent attempt succeeds with 201 Created; subsequent replay returns 200 OK without re-triggering failures.
  - `TIMEOUT_AFTER_SUCCESS`: Client read timeout (200ms) against server delay (800ms) causes client-side timeout; status query (`GET /api/provider/operations/by-client/{clientOperationId}`) proves operation was already committed and `SUCCEEDED` in the database prior to the timeout.
  - `DELAYED_WEBHOOK`: Operation returns 201 immediately; webhook is delivered only after configured delay has elapsed.
  - `DUPLICATE_WEBHOOK`: Operation schedules 2 delivery rows; receiver observes 2 HTTP callbacks carrying the exact same `eventId` and payload.
  - `WEBHOOK_DELIVERY_FAILURE`: Unreachable webhook endpoint transitions delivery row to `FAILED` without affecting provider operation `SUCCEEDED` status.
  - `SCENARIO_ISOLATION`: Injected scenario on Client A does not alter normal behavior on Client B.

---

## 5. External Wallet Funding Testing Strategy (Phase 20)

- **Database Constraint Verification (`FundingDatabaseConstraintTest`)**:
  - Direct insert with `PROCESSING` succeeds for active INR customer account owned by initiator.
  - Direct insert with `SUCCEEDED` rejected by trigger.
  - Direct insert with wrong account type (`MERCHANT`, `PSP_CLEARING`), wrong currency, wrong owner, closed account, or invalid amounts ($\le 0$) rejected.
  - Valid transition `PROCESSING -> SUCCEEDED` requires `provider_operation_id`, `completed_at`, and an existing balanced `POSTED` settlement journal.
  - Transition rejected if settlement journal has mismatched amounts or wrong accounts.
  - Completed funding operations are immutable and cannot be updated or deleted.
- **Service & Integration Invariant Verification (`FundingServiceIntegrationTest`, `FundingControllerIntegrationTest`)**:
  - **Normal Settlement**: Verifies atomic DEBIT `PSP_CLEARING` and CREDIT customer wallet, updating snapshots and creating 1 journal transaction.
  - **Existing Balances & Holds**: Funds correctly increase posted and available balances without overwriting existing balances or disturbing active holds.
  - **No Active DB Transaction During Network I/O**: Verifies `TransactionSynchronizationManager.isActualTransactionActive()` is false during external PSP HTTP requests.
  - **Ambiguity & Network Timeouts (`TIMEOUT_AFTER_SUCCESS`)**: Read timeout during PSP call preserves `PROCESSING` status with 0 wallet credit; matching replay with same `Idempotency-Key` resolves the existing provider operation and settles the ledger.
  - **Provider 5xx Faults (`TEMPORARY_500`)**: Preserves `PROCESSING` status and 0 wallet credit; subsequent replay retries the call using the same `FundingOperation.id` as `clientOperationId`, settling upon recovery.
  - **Provider Response Integrity Mismatch**: Mismatched currency, amount, type, or clientOperationId fails validation and preserves `PROCESSING` status with 0 wallet credit.
  - **Concurrent Idempotency**: 20 concurrent threads with identical request and idempotency key produce exactly 1 `FundingOperation`, 1 settlement journal, and 1 credit.
  - **High-Precision Money**: Handles large integer values ($> \text{Number.MAX\_SAFE\_INTEGER}$) without precision loss.
  - **Fails Closed on Missing/Multiple Clearing Accounts**: Rejects settlement if 0 or $>1$ active `PSP_CLEARING` accounts exist.

---

## 6. External Payouts / Withdrawals Testing Strategy (Phase 21)

- **Database Constraint Verification (`PayoutDatabaseConstraintTest`)**:
  - Direct insert with `PROCESSING` succeeds for active INR customer or merchant account owned by initiator, linking to an `ACTIVE` hold.
  - Direct insert with `SUCCEEDED` or `FAILED` rejected by trigger.
  - Direct insert with wrong account type (`PSP_CLEARING`), wrong currency, wrong owner, closed account, or invalid amounts ($\le 0$) rejected.
  - Valid transition `PROCESSING -> SUCCEEDED` requires `provider_operation_id`, `completed_at`, a `CONSUMED` hold, and an existing balanced `POSTED` settlement journal (DEBIT source wallet, CREDIT `PSP_CLEARING`).
  - Valid transition `PROCESSING -> FAILED` requires a `RELEASED` hold and zero settlement journal.
  - Transition rejected if settlement journal has mismatched amounts, wrong accounts, or if hold status is not `CONSUMED` (for SUCCEEDED) / `RELEASED` (for FAILED).
  - Completed payout operations (`SUCCEEDED`, `FAILED`) are immutable and cannot be updated or deleted.
- **Service & Integration Invariant Verification (`PayoutServiceIntegrationTest`, `PayoutControllerIntegrationTest`)**:
  - **Normal Settlement (Customer & Merchant)**: Verifies pre-network hold reservation (`ACTIVE`), authoritative provider `SUCCEEDED` response, hold consumption (`CONSUMED`), and atomic DEBIT source wallet to CREDIT `PSP_CLEARING` settlement journal.
  - **Definite Provider Failure (`TEMPORARY_500` / `FAILED`)**: Definite provider failure releases hold (`RELEASED`), marks payout `FAILED`, with 0 journal entries and 0 balance reduction.
  - **Ambiguity & Network Timeouts (`TIMEOUT_AFTER_SUCCESS`)**: Read timeout preserves payout `PROCESSING` status, retains `ACTIVE` hold, posts 0 journal, and returns HTTP 202 Accepted.
  - **Matching Replay Policy**:
    - Replay of `SUCCEEDED` payout -> returns 200 OK with `replayed=true` (0 PSP calls, 0 journal).
    - Replay of `FAILED` payout -> returns 200 OK with `replayed=true` (0 PSP calls, 0 journal).
    - Replay of `PROCESSING` payout -> returns 202 Accepted with `replayed=true` (0 new PSP calls in Phase 21).
  - **Hold Expiration Protection**: Background generic hold expiration queries explicitly ignore holds linked to `PROCESSING` payouts, ensuring in-flight withdrawals are never cancelled prematurely.
  - **Insufficient Funds / Capacity**: Payout creation rejected if `availableBalanceMinor < requestedAmountMinor`.
  - **Concurrent Idempotency**: Concurrent requests with identical `(actor, idempotency_key)` execute hold reservation and payout pipeline safely with single execution.

---

## 7. Provider Webhook Ingress & Processing Testing Strategy (Phase 22)

- **Database Constraint Verification (`ProviderEventDatabaseConstraintTest` â€” 17 Tests)**:
  - Sequence constraint: `event_sequence >= 1`.
  - Amount constraint: `amount_minor > 0`.
  - Currency constraint: `currency = 'INR'`.
  - Operation type constraint: `operation_type IN ('CREDIT', 'DEBIT')`.
  - Provider status constraint: `provider_status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')`.
  - Processing status constraint: `processing_status IN ('PENDING', 'APPLIED', 'IGNORED')`.
  - Event type match: `eventType` must match `providerStatus` (`PROVIDER_OPERATION_PROCESSING`, `PROVIDER_OPERATION_SUCCEEDED`, `PROVIDER_OPERATION_FAILED`).
  - JSON payload validation: must be valid JSON object, not null or array.
  - Lifecycle trigger enforcement:
    - Direct insert with `APPLIED` or `IGNORED` strictly rejected by trigger.
    - Direct insert with non-null `processed_at` strictly rejected.
    - Valid insert requires `processing_status = 'PENDING'` and `processed_at IS NULL`.
    - Update mutates immutable business columns rejected.
    - Update `PENDING -> APPLIED` with non-null `processed_at` succeeds.
    - Update `PENDING -> IGNORED` with non-null `processed_at` succeeds.
    - Update on terminal status (`APPLIED` or `IGNORED`) rejected.
    - Direct `DELETE` strictly rejected.
  - Unique constraint on `(provider_operation_id, event_sequence)` enforced.
- **Authentication & Ingress Security Verification (`ProviderWebhookAuthenticationIntegrationTest` â€” 13 Tests)**:
  - Valid signature and timestamp returns HTTP 200 OK.
  - Missing timestamp header returns HTTP 401 Unauthorized.
  - Missing signature header returns HTTP 401 Unauthorized.
  - Expired timestamp (>300s in past) returns HTTP 401 Unauthorized.
  - Future timestamp (>300s in future) returns HTTP 401 Unauthorized.
  - Malformed non-numeric timestamp returns HTTP 401 Unauthorized.
  - Invalid signature format (uppercase hex or missing `sha256=`) returns HTTP 401 Unauthorized.
  - Incorrect shared secret returns HTTP 401 Unauthorized.
  - Tampered payload bytes (even single space) returns HTTP 401 Unauthorized.
  - Secret leakage prevention: authentication failure responses never echo or expose the secret.
  - Extreme timestamp values safely rejected: `Long.MAX_VALUE` and `Long.MIN_VALUE` handled without overflow/panic, returning HTTP 401 Unauthorized.
  - Malformed signature formats rejected: missing `sha256=` prefix, 63 hex chars, 65 hex chars, and non-hex characters all rejected with HTTP 401 Unauthorized.
  - Raw body sensitivity: semantically equivalent JSON with modified whitespace/formatting fails signature verification with HTTP 401 Unauthorized.
  - Timestamp boundary validation: timestamps inside window ($\pm 290\text{s}$) accepted, timestamps outside window ($\pm 305\text{s}$) rejected with HTTP 401 Unauthorized.
- **Deduplication, Ordering & Settlement Verification (`ProviderWebhookProcessingIntegrationTest` â€” 16 Tests)**:
  - `CREDIT SUCCEEDED` settles funding operation and posts double-entry settlement journal.
  - Identical redelivered webhook returns 200 OK without creating duplicate rows or journals.
  - Same-terminal progression (`SUCCEEDED -> SUCCEEDED`) marks event `APPLIED` with zero new journals.
  - `DEBIT SUCCEEDED` settles payout, consumes balance hold, marks event `APPLIED`.
  - `DEBIT FAILED` releases balance hold, marks payout `FAILED`, 0 journals.
  - `CREDIT FAILED` observation-only: marks event `APPLIED`, leaves funding `PROCESSING`, 0 journals.
  - Out-of-order delivery: sequence 2 returns 202 ACCEPTED and remains `PENDING`; subsequent sequence 1 unblocks sequence 2 in order.
  - Status regression (`SUCCEEDED -> PROCESSING`) marks event `IGNORED` with zero financial effect.
  - Sequence ownership conflict (different eventId for same providerOpId and sequence) returns 409 Conflict.
  - Changed payload for existing eventId returns 409 Conflict.
  - 20 concurrent identical deliveries yield exactly 1 event row, 1 journal, and zero errors.
  - Concurrent sequence ownership race: exactly 1 sequence owner succeeds, second request receives 409 Conflict, 0 duplicate settlements.
  - Duplicate PENDING redelivery retries processing: duplicate redelivery of an event left `PENDING` due to crash window successfully retries and completes `PENDING -> APPLIED` settlement transition without duplicate journals.
  - Conflicting provider operation for same clientOperationId: sequential delivery of event from different providerOperationId for already settled operation rejected with HTTP 409 Conflict (`PROVIDER_EVENT_CONFLICT`), journals $\le 1$.
  - Concurrent conflicting provider operations: multi-threaded race with different providerOperationIds for same clientOperationId serializes under row lock, exactly one wins (200 OK), loser receives HTTP 409 Conflict (`PROVIDER_EVENT_CONFLICT`), exactly 1 journal posted.
  - Different providerOperationId on settled payout returns HTTP 409 Conflict without duplicate hold release or settlement.
- **Real External Callback End-to-End Verification (`ProviderRealCallbackE2EIntegrationTest` â€” 1 Test)**:
  - LedgerGuard real-HTTP callback E2E using a faithful PSP test server: verifies `TIMEOUT_AFTER_SUCCESS` payout workflow over real HTTP sockets. `PayoutService.requestPayout` makes real HTTP DEBIT call via `PspClient`; faithful test server simulates synchronous read timeout (300ms) while committing `SUCCEEDED` remotely; Payout remains `PROCESSING` with `ACTIVE` hold; test server dispatches signed HTTP webhook callback to LedgerGuard's live HTTP server port (`server.port=8089`); LedgerGuard HTTP ingress receives, authenticates HMAC-SHA256, records event in `provider_events`, and processes settlement; Payout transitions to `SUCCEEDED`, hold is `CONSUMED`, exactly 1 journal posted.
- **Actual PSP Simulator Outbound Signing & Storage Verification (`ProviderWebhookSigningIntegrationTest` â€” 2 Tests)**:
  - Proves actual `psp-simulator` Spring application, `provider_webhooks` database table, and `ProviderWebhookDispatcher`: outbound webhook includes `eventSequence: 1` in stored payload and valid HMAC-SHA256 signature matching canonical bytes with delivery timestamp headers (`X-PSP-Webhook-Timestamp`, `X-PSP-Webhook-Signature`).
  - `DUPLICATE_WEBHOOK` scenario dispatches byte-for-byte identical payloads with valid signatures computed per delivery timestamp.

---

## 10. Phase 23: External State Machine & Ambiguous Outcome Lifecycle Tests

Phase 23 introduces a dedicated lifecycle test suite in `ledgerguard-api` under `com.ledgerguard.lifecycle` (8 test classes, 20 test methods). All tests run against real PostgreSQL Testcontainers (`AbstractIntegrationTest`). No H2, no mocking of the DB layer.

### Test Class Registry

| Test Class | Methods | Coverage Area |
| :--- | :---: | :--- |
| `ConcurrentSubmissionClaimIntegrationTest` | 1 | At-most-one provider POST via atomic `CREATED -> PROCESSING` claim race |
| `DurableConflictTransitionIntegrationTest` | 1 | Conflicting replay: `PROCESSING -> RECONCILIATION_REQUIRED` durably committed before HTTP 409 |
| `ExternalStateMachineDatabaseConstraintTest` | 7 | V13 trigger enforcement: all illegal status transitions, field invariants, and delete rejection |
| `FinalAttemptExhaustionRaceIntegrationTest` | 1 | Concurrent poller exhaustion: exactly one `PROCESSING -> RECONCILIATION_REQUIRED` under race |
| `MigrationV13CompatibilityTest` | 1 | V13 Flyway migration: new columns, backfill, CHECK constraints, trigger enforcement |
| `PspErrorClassificationIntegrationTest` | 5 | RFC-9457 ProblemDetail `type` URI classification: temporary-failure, conflicting-replay, ambiguous 500, transport timeout, missing body |
| `TerminalProviderContradictionIntegrationTest` | 3 | FAILED + FAILED idempotent; SUCCEEDED + FAILED conflict; FAILED + SUCCEEDED conflict; journal integrity on settlement |
| `TimeoutAfterSuccessE2EIntegrationTest` | 1 | Full E2E: `TIMEOUT_AFTER_SUCCESS` scenario â€” CREATED â†’ PROCESSING â†’ UNKNOWN â†’ SUCCEEDED via poller GET; exactly 1 journal (2 balanced entries, 1 DEBIT + 1 CREDIT); hold CONSUMED; no duplicate journals |

### Phase 23 Lifecycle Invariants Verified by Tests

1. **At-Most-One Provider POST**: `ConcurrentSubmissionClaimIntegrationTest` races concurrent threads; only one claims `CREATED -> PROCESSING`; exactly 1 external POST made.
2. **Durable Conflict Before HTTP**: `DurableConflictTransitionIntegrationTest` confirms `RECONCILIATION_REQUIRED` is in the DB row before any 409 is returned.
3. **V13 Trigger Boundaries**: `ExternalStateMachineDatabaseConstraintTest` directly exercises every prohibited transition and field invariant at the SQL level.
4. **Exhaustion Race Safety**: `FinalAttemptExhaustionRaceIntegrationTest` proves exactly 1 row reaches `RECONCILIATION_REQUIRED` when multiple pollers race to finalize.
5. **Error Classification (RFC-9457)**: `PspErrorClassificationIntegrationTest` verifies `urn:ledgerguard:psp:error:temporary-failure` â†’ `FAILED` (hold `RELEASED`), `urn:ledgerguard:psp:error:conflicting-replay` â†’ `RECONCILIATION_REQUIRED`, and all ambiguous variants â†’ `UNKNOWN` (hold `ACTIVE`).
6. **Terminal Contradiction Safety**: `TerminalProviderContradictionIntegrationTest` verifies that conflicting terminal outcomes throw `ProviderEventConflictException`, total journals â‰¤ 1, and no duplicate financial mutations occur.
7. **Full Lifecycle Journal Integrity**: `TimeoutAfterSuccessE2EIntegrationTest` and `TerminalProviderContradictionIntegrationTest` both verify exactly 1 `POSTED` journal transaction, exactly 2 journal entries (1 `DEBIT` + 1 `CREDIT`), equal `amount_minor`, and exactly 1 journal per operation (no duplicates).

### Phase 23 Test Count

- `ledgerguard-api`: **450 tests, 0 failures, 0 errors, 0 skipped**
- `psp-simulator`: **17 tests**
- `notification-worker`: **18 tests**
- `failure-lab`: **1 test**
- **Workspace total: 486 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-02).

---

## 11. Phase 24: Core Reconciliation Engine Testing Strategy

Phase 24 introduces a dedicated test suite in `ledgerguard-api` under `com.ledgerguard.reconciliation` (6 test classes, 42 test methods). All tests execute against PostgreSQL 17-alpine Testcontainers.

### Test Class Registry

| Test Class | Methods | Coverage Area |
| :--- | :---: | :--- |
| `ReconciliationV14MigrationTest` | 13 | V14 triggers: valid runs, terminal immutability, item immutability, lock escalation, cross-column CHECK constraints |
| `ReconciliationRunLifecycleTest` | 4 | Run completion, failure, terminal counter derivation, concurrent finalization serialization |
| `JournalBalanceCheckerIntegrationTest` | 6 | Level 1: healthy journals, zero-entry detection (LEFT JOIN), unbalanced journals, test-only trigger disable/enable, no-repair proof |
| `SnapshotConsistencyCheckerIntegrationTest` | 4 | Level 2: healthy snapshots, DRAFT entry exclusion, snapshot balance corruption detection, missing snapshot detection, no-repair proof |
| `ProviderSettlementCheckerIntegrationTest` | 12 | Level 3: healthy matches, status mismatches, amount/currency/id mismatches, NOT_FOUND handling, in-doubt processing, provider unavailable transport/protocol errors |
| `ReconciliationEngineIntegrationTest` | 3 | End-to-end: scheduled vs on-demand runs, 3-level integrated corruption detection with no financial repair, and failure recovery |

### Phase 24 Test Count

- `ledgerguard-api`: **497 tests, 0 failures, 0 errors, 0 skipped** (with final provider-edge corrections)
- `psp-simulator`: **17 tests**
- `notification-worker`: **18 tests**
- `failure-lab`: **1 test**
- **Workspace total: 533 tests, 0 failures, 0 errors, 0 skipped**

---

## 12. Phase 25: Reconciliation Recovery & Manual Review Testing Strategy

Phase 25 introduces a dedicated test suite in `ledgerguard-api` under `com.ledgerguard.reconciliation` (6 test classes, 37 test methods). All tests execute against PostgreSQL 17-alpine Testcontainers.

### Test Class Registry

| Test Class | Methods | Coverage Area |
| :--- | :---: | :--- |
| `ReconciliationV15MigrationTest` | 10 | V15 migration triggers: OPEN status requirement, null-safe `IS DISTINCT FROM` claim immutability (reassignment and unassignment blocks), terminal immutability, DELETE rejection, immutable identity columns, auto-case creation trigger on `reconciliation_items` |
| `ReconciliationCaseLifecycleTest` | 8 | Domain lifecycle: idempotent claim by same operator, 409 conflict on competing operator, resolve with note, rejection of resolve on `SNAPSHOT_MISMATCH`, note length bounds (<= 1000), clock skew normalization |
| `SnapshotAutoRepairTest` | 7 | Snapshot dynamic reconstruction: credit-normal and debit-normal accounts, `SNAPSHOT_REPAIRED`, `ALREADY_CONSISTENT`, idempotent repair replay, missing snapshot row rejection (409 Conflict), signed 64-bit bounds check |
| `ConcurrentPostingSnapshotRepairTest` | 1 | Multi-threaded race (20 threads concurrent double-entry posting vs 1 thread auto-repair) proving row lock serialization on target snapshot and 100% mathematical consistency without lost postings |
| `ManualReviewNoMutationTest` | 1 | Proof of zero financial mutations across 9 tables (`journal_transactions`, `journal_entries`, `ledger_balance_snapshots`, `funding_operations`, `payouts`, `balance_holds`, `provider_events`, `outbox_events`, `idempotency_records`) during manual review |
| `ReconciliationSecurityAndApiTest` | 10 | REST API security (`ROLE_OPS` enforcement, 403 Forbidden for `CUSTOMER`/`MERCHANT`), bounded pagination (clamped to 100), filter queries, exact numeric string serialization (`toPlainString()`), end-to-end claim and resolution flows |

### Phase 25 Test Count

- `ledgerguard-api`: **537 tests, 0 failures, 0 errors, 0 skipped** (+40 tests from Phase 24)
- `psp-simulator`: **17 tests**
- `notification-worker`: **18 tests**
- `failure-lab`: **1 test**
- **Workspace total: 573 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-04).

---

## 13. Phase 26: Resilient Provider Client Testing Strategy

Phase 26 introduces a resilient provider client execution layer powered by Resilience4j 2.4.0 core modules (`resilience4j-circuitbreaker`, `resilience4j-retry`, `resilience4j-bulkhead`) integrated programmatically without AOP or Spring Boot starter magic. All resilience components are verified through isolated unit tests (`PspClientResilienceUnitTest`) and end-to-end Spring Boot Testcontainers integration tests (`ProviderResilienceIntegrationTest`).

### Test Class Registry

| Test Class | Methods | Coverage Area |
| :--- | :---: | :--- |
| `PspClientResilienceUnitTest` | 13 | Resilience4j programmatic pipeline: circuit breaker transitions (CLOSED -> OPEN -> HALF_OPEN -> CLOSED), retry backoff & jitter capping, decorator invocation order (`CircuitBreaker -> Bulkhead -> Aggregate Logical Outcome -> Retry -> Raw RestClient HTTP`), isolated `psp-create` and `psp-status` bulkhead saturation, fast rejection on open circuit, pre-network rejection hold release, transaction boundary verification (asserts no active DB transaction around resilience calls) |
| `ProviderResilienceIntegrationTest` | 7 | Full lifecycle resilience: authoritative replay of `TIMEOUT_AFTER_SUCCESS` resolving to `SUCCEEDED` with double-entry journal and consumed hold, multi-attempt ambiguity dominance (timeout followed by 5xx preserved as `UNKNOWN` with `ACTIVE` hold), poll attempt counter incremented exactly once per logical poll despite retries, status bulkhead saturation non-starvation of create pipeline, circuit breaker half-open auto-recovery on canary success, pre-network circuit open rejection with hold release, and reconciliation Level 3 marking `UNRESOLVED` + `PROVIDER_UNAVAILABLE` on circuit open and bulkhead saturation |

### Phase 26 Invariants Verified by Tests

1. **Network Transaction Boundary**: `PspClientResilienceUnitTest.verifyTransactionBoundaryOutsideResilience` verifies that `TransactionSynchronizationManager.isActualTransactionActive() == false` across all resilient client invocations, guaranteeing that retries, backoffs, and circuit breaker evaluation never hold open PostgreSQL database transactions or locks.
2. **Deterministic Decorator Order**: Validates the strict sequence `CircuitBreaker -> Bulkhead -> Aggregate Logical Outcome (CREATE) -> Retry -> Raw RestClient HTTP` (and `CircuitBreaker -> Bulkhead -> Retry -> Raw RestClient HTTP` for status GET). CircuitBreaker is outermost so an OPEN circuit fast-rejects before bulkhead acquisition and before any HTTP call. Bulkhead wraps Retry so one permit covers the entire bounded logical provider interaction. Retry sits inside so multiple physical retries reuse a single bulkhead slot, while individual physical failures are recorded in the circuit breaker metric ring.
3. **Separate Bulkhead Concurrency Domains**: Confirms `psp-create` and `psp-status` operate on completely independent bulkhead instances (each defaulted to 20 concurrent calls with 0ms wait duration). Saturating the status bulkhead returns `BulkheadFullException` on status calls while create calls proceed without thread starvation.
4. **Authoritative Replay Resolution (`TIMEOUT_AFTER_SUCCESS`)**: Proves that when an initial POST encounters a transport timeout but commits remotely, an immediate retry or subsequent status query returns the authoritative `SUCCEEDED` provider operation. The logical operation transitions to `SUCCEEDED`, commits the double-entry journal, and consumes the balance hold.
5. **Multi-Attempt Ambiguity Dominance**: Verifies that when any physical attempt within a logical retry cycle yields an ambiguous outcome (transport timeout or non-deterministic 5xx), subsequent physical failures (even machine-readable `temporary-failure`) do NOT demote the outcome to `FAILED`. The final business status is preserved as `UNKNOWN` with the balance hold remaining `ACTIVE`.
6. **Poller Counter Retry Isolation**: Verifies that when the background status poller executes a logical poll that triggers physical Resilience4j retries, `provider_poll_attempts` is incremented exactly once for the logical poller cycle, preventing premature poll exhaustion.
7. **Reconciliation Provider Unavailable Classification**: Asserts that when Level 3 `ProviderSettlementChecker` encounters an open circuit breaker or saturated bulkhead, it records an item with `classification = UNRESOLVED` and `problem_type = ReconciliationProblemType.PROVIDER_UNAVAILABLE`, leaving the frozen V14 schema intact and making zero financial mutations.

### Phase 26 Test Count

- `ledgerguard-api`: **557 tests, 0 failures, 0 errors, 0 skipped** (+20 tests from Phase 25)
- `psp-simulator`: **17 tests**
- `notification-worker`: **18 tests**
- `failure-lab`: **1 test**
- **Workspace total: 593 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-05).

---

## 14. Phase 27: Rate Limiting & Bounded Backpressure Testing Strategy

Phase 27 introduces token-bucket admission control, bounded server thread pools, and bounded Kafka consumer backpressure. The test suite verifies security precedence, tenant/principal isolation, financial safety on HTTP 429 rejections, thread/connection bounds, and consumer concurrency.

### Test Class Registry

| Test Class | Module | Methods | Coverage Area |
| :--- | :--- | :---: | :--- |
| `RateLimitServiceUnitTest` | `ledgerguard-api` | 5 | Token consumption: consumption probe rejection when quota exhausted, greedy refill calculation, distinct cache entries for distinct keys, idle TTL eviction, bypass when `enabled = false`. |
| `RateLimitFilterUnitTest` | `ledgerguard-api` | 6 | Mock filter pipeline: `PUBLIC_AUTH` IP keying, authenticated user UUID keying, exempt endpoints bypass (`OPTIONS`, `/actuator/health`, `/api/provider/webhooks`), RFC 9457 ProblemDetail response serialization with `RATE_LIMIT_EXCEEDED` and `Retry-After` header. |
| `RateLimitSecurityPrecedenceIntegrationTest` | `ledgerguard-api` | 6 | Spring Security precedence against real security chain: missing token returns 401 Unauthorized without consuming tokens; invalid token returns 401 without consuming tokens; role-forbidden request (`CUSTOMER` hitting `/api/ops/**`) returns 403 Forbidden without consuming tokens; repeated forbidden requests never return 429; authenticated requests after 403 succeed if quota permits. |
| `RateLimitIntegrationTest` | `ledgerguard-api` | 5 | Multi-threaded concurrency and financial safety against PostgreSQL Testcontainers: 50 concurrent requests against capacity 5 yield exactly 5 admitted and 45 HTTP 429 rejections; principal isolation (User A exhausted, User B unaffected); public auth login flood throttled by IP; financial safety invariant (asserts 0 `idempotency_records`, 0 `transfers`, 0 journals, 0 hold mutations on 429); Hikari connection pool conservation (all active connections returned during burst). |
| `TomcatThreadPropertiesTest` | `ledgerguard-api` | 2 | Bounded server execution verification: asserts production `application.yml` configures Tomcat `max=50`, `min-spare=10`, `max-queue-capacity=50`, `accept-count=50`, `max-connections=1000`, and Hikari `maximum-pool-size=10`. Verifies runtime pool size $\le 10$. |
| `NotificationWorkerApplicationTests` | `notification-worker` | 2 | Context load and bounded Kafka consumer backpressure: verifies `ConcurrentKafkaListenerContainerFactory` is configured with `concurrency = 3` and `ConsumerConfig.MAX_POLL_RECORDS_CONFIG = 10`. |

### Phase 27 Invariants Verified by Tests

1. **Security Precedence**: `RateLimitSecurityPrecedenceIntegrationTest` proves that 401 Unauthorized and 403 Forbidden strictly precede rate limit evaluation. Unauthenticated and forbidden requests never consume token quota, and repeating unauthorized/forbidden calls never triggers a 429 response.
2. **Deterministic Token-Bucket Admission**: `RateLimitIntegrationTest.burstRequestsExceedingCapacity` proves that under high concurrency (50 threads), exactly 5 requests are admitted and 45 requests receive HTTP 429 Too Many Requests with an integer `Retry-After` header.
3. **Principal & Identity Isolation**: `RateLimitIntegrationTest.userQuotaIsolation` verifies that exhausting User A's token bucket has zero impact on User B's ability to execute requests. `PUBLIC_AUTH` is strictly isolated per client IP.
4. **Financial Safety Invariant**: `RateLimitIntegrationTest.rateLimitedRequestCausesNoFinancialSideEffects` verifies that an HTTP 429 rejection on a financial write (`POST /api/transfers`) produces zero rows in `idempotency_records`, zero rows in `transfers`, and zero journal transactions or entries. Subsequent replay with the same idempotency key after token refill executes cleanly as the first admitted transaction.
5. **Connection Pool Conservation**: Tests verify that during a flood of 429 rejections, zero Hikari database connections are checked out, preventing connection starvation.
6. **Bounded Consumer Backpressure**: `NotificationWorkerApplicationTests.verifyKafkaConsumerBackpressureConfiguration` verifies that the Kafka consumer container factory restricts batch size to 10 records and bounds listener thread concurrency to 3.

### Phase 27 Test Count

- `ledgerguard-api`: **583 tests, 0 failures, 0 errors, 0 skipped** (+26 tests from Phase 26)
- `psp-simulator`: **17 tests**
- `notification-worker`: **19 tests** (+1 test from Phase 26)
- `failure-lab`: **1 test**
- **Workspace total: 620 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-05).
