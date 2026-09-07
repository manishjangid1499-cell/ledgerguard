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

---

## 9. Phase 28 — Audit Trail & Security Hardening Test Suite

Phase 28 introduces database-enforced immutable operational audit logging, transactional atomicity, raw control character input sanitization, and security response header hardening.

### Test Class Registry

| Test Class | Module | Methods | Coverage Area |
| :--- | :--- | :---: | :--- |
| `ResolutionNoteValidationTest` | `ledgerguard-api` | 16 | Raw control character rejection: NUL (0x00), CR (0x0D), LF (0x0A), TAB (0x09), DEL (0x7F), intermediate/leading/trailing C0 controls; non-blank validation; length bounds ($\le 1000$ characters); valid printable Unicode strings and space normalization. |
| `SecurityHeadersIntegrationTest` | `ledgerguard-api` | 7 | Spring Security response headers: explicit CSP (`default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, explicit HSTS (`max-age=31536000; includeSubDomains`) on HTTPS, absence of HSTS on plain HTTP, CORS configured-origin preflight credentials + `Retry-After` header exposure, untrusted origin rejection, and wildcard rejection with credentials. |
| `AuditTrailIntegrationTest` | `ledgerguard-api` | 15 | Audit persistence & invariants: `Propagation.MANDATORY` enforcement without transaction; absence of public Map methods via reflection; database trigger rejection of `UPDATE`, `DELETE`, and `TRUNCATE`; database clock timestamp generation; audit on case claim; zero audit on duplicate case claim; audit on manual resolution (with note excluded from details JSON); audit on snapshot repair; audit on already-consistent snapshot; rollback of audit on business conflict; rollback of business mutation on audit failure; rollback of snapshot repair on audit failure (snapshot balance, timestamp, and case restored); rollback of already-consistent resolution on audit failure; concurrent claim serialization (exactly 1 audit row). |

### Phase 28 Invariants Verified by Tests

1. **Database Immutability Triggers**: `AuditTrailIntegrationTest` proves that direct SQL `UPDATE`, `DELETE`, and `TRUNCATE` operations on `audit_events` are strictly rejected with an exception by PostgreSQL trigger `trg_audit_events_immutability`.
2. **Transactional Audit Atomicity**: `AuditTrailIntegrationTest` proves that an audit event insert is rolled back if the surrounding business transaction encounters a conflict. Conversely, if audit persistence fails, the business mutation rolls back completely, including restoring snapshot balances, timestamps, and case states.
3. **Idempotent Replay Isolation**: `AuditTrailIntegrationTest` verifies that replaying an already claimed or resolved case emits zero additional audit events, ensuring 1:1 correspondence between audit records and state transitions.
4. **Authoritative Engine Timestamp**: `AuditTrailIntegrationTest` confirms that `occurred_at` is generated authoritatively by PostgreSQL's `DEFAULT NOW()` within a bounded execution window.
5. **Raw Input Hardening**: `ResolutionNoteValidationTest` proves that raw notes containing control characters (CR, LF, TAB, NUL, DEL) at the beginning, end, or middle are rejected with `INVALID_RECONCILIATION_OPERATION` before any whitespace trimming takes place.
6. **Security Header Delivery**: `SecurityHeadersIntegrationTest` proves that every response carries explicit CSP, `nosniff`, and `DENY` headers, and that HTTPS endpoints deliver the explicit 1-year HSTS header.

### Phase 28 Test Count

- `ledgerguard-api`: **621 tests, 0 failures, 0 errors, 0 skipped** (+38 tests from Phase 27)
- `psp-simulator`: **17 tests**
- `notification-worker`: **19 tests**
- `failure-lab`: **1 test**
- **Workspace total: 658 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-05).

---

## 10. Phase 29 — Business & Integrity Metrics (Prometheus) Test Suite

Phase 29 introduces Prometheus metric exposition via Micrometer, four custom business metrics, consolidated database sampling, in-memory atomic snapshot caching, and scheduler isolation.

### Test Class Registry

| Test Class | Module | Methods | Coverage Area |
| :--- | :--- | :---: | :--- |
| `IntegrityMetricsPropertiesValidationTest` | `ledgerguard-api` | 5 | Configuration validation: positive sample intervals accepted, zero/negative sample intervals rejected via Jakarta validation, default values verified (`15s`, `true`), and scheduler disablement verified. |
| `IntegrityMetricsUnitTest` | `ledgerguard-api` | 5 | In-memory unit behavior: initial zero values, atomic snapshot reference updates, gauge observation consistency across rapid updates, duplicate counter incrementing with bounded `reason` tags (`replay`, `fingerprint_conflict`, `in_progress`), and reflection-based tag verification. |
| `IntegrityMetricsSamplerUnitTest` | `ledgerguard-api` | 5 | Background sampler unit behavior: successful snapshot transition and atomic update, transition-aware failure logging (1 WARN on initial outage transition, repeated failures log DEBUG, 1 INFO on recovery, new WARN on second outage), database detail and stack trace suppression, and scheduler disabled suppression. |
| `IntegrityMetricsSnapshotReaderIntegrationTest` | `ledgerguard-api` | 7 | Consolidated SQL query integration against PostgreSQL: verifies single consolidated query returns all 3 metrics with 100% precision across baseline state, unbalanced journal detection, explicit zero-entry POSTED journal detection via LEFT JOIN with trigger restoration verification, reconciliation discrepancy lifecycle (OPEN/IN_REVIEW vs RESOLVED), pending outbox lag calculation, future timestamp clamping via GREATEST(0, ...), and empty outbox COALESCE behavior. |
| `ActuatorPrometheusIntegrationTest` | `ledgerguard-api` | 8 | End-to-end Actuator & Security: `/actuator/prometheus` returns HTTP 200 with `text/plain; version=0.0.4`, unauthenticated access succeeds (`permitAll`), rate limiting is bypassed (`RateLimitPolicy.EXEMPT`), all 4 custom metrics appear eagerly on startup, invalid journal detection reflects live in gauge, active reconciliation discrepancy reflects live in gauge, oldest pending outbox lag reflects live in gauge, and duplicate idempotency counters increment accurately for `replay`, `fingerprint_conflict`, and `in_progress`. |

### Phase 29 Invariants Verified by Tests

1. **Decoupled Scrape Execution**: `ActuatorPrometheusIntegrationTest` verifies that `/actuator/prometheus` serves all gauges and counters directly from memory without executing live database queries during the scrape.
2. **Consolidated Snapshot Integrity**: `IntegrityMetricsSnapshotReaderIntegrationTest` proves that a single consolidated SQL statement reads invalid journals (including zero-entry POSTED journals via `LEFT JOIN`), discrepancy cases, and pending outbox lag in one round-trip under a read-only transaction.
3. **Transition-Aware Sampler Logging**: `IntegrityMetricsSamplerUnitTest` proves that consecutive database failures emit exactly 1 `WARN` on the initial failure transition, 0 additional `WARN` logs for subsequent failures, 1 `INFO` on recovery, and allow a new `WARN` only upon a subsequent outage transition.
4. **Live Metric Reflection**: `ActuatorPrometheusIntegrationTest` injects and resolves ledger anomalies, outbox events, and discrepancy cases, proving that subsequent sampling dynamically reflects in the Prometheus scrape output.
5. **Bounded Reason Cardinality**: `IntegrityMetricsUnitTest` and `ActuatorPrometheusIntegrationTest` verify that `duplicate_idempotency_keys_total` uses strictly bounded tags (`reason` = `replay`, `fingerprint_conflict`, `in_progress`) with zero UUID or key leaks.
6. **Scheduler Isolation in Tests**: Integration test suites run with `ledgerguard.metrics.integrity.scheduler-enabled: false`, ensuring background threads do not introduce non-deterministic race conditions during test execution.

### Phase 29 Test Count

- `ledgerguard-api`: **651 tests, 0 failures, 0 errors, 0 skipped** (+30 tests from Phase 28 baseline of 621)
- `psp-simulator`: **17 tests**
- `notification-worker`: **19 tests**
- `failure-lab`: **1 test**
- **Workspace total: 688 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-05).
---

## 11. Phase 30 — OpenTelemetry Tracing & Correlation IDs Test Suite

Phase 30 introduces end-to-end distributed tracing using Micrometer Tracing with OpenTelemetry bridge, correlation ID ingress filtering and sanitization, structured MDC logging, Flyway V17 durable outbox trace context persistence, Kafka trace header deduplication, notification worker event observation, and actuator exposure lockdown.

### Test Class Registry

| Test Class | Module | Methods | Coverage Area |
| :--- | :--- | :---: | :--- |
| `CorrelationIdFilterTest` | `ledgerguard-api` | 11 | Ingress correlation filter unit tests: valid custom correlation ID preservation, missing header fallback to UUID, blank/whitespace fallback to UUID, CRLF injection sanitization, control characters/null bytes sanitization, excessive length (>64 chars) rejection, response header echoing, sequential thread reuse zero MDC leakage, outer MDC context restoration, and disallowed character sanitization (script/quotes/spaces/DEL). |
| `CorrelationIdSecurityIntegrationTest` | `ledgerguard-api` | 5 | Security filter chain integration: 401 Unauthorized returns `X-Correlation-Id`, unauthenticated Actuator health returns `X-Correlation-Id`, CORS preflight exposes `X-Correlation-Id` in `Access-Control-Expose-Headers` alongside `Retry-After`, and header sanitization operates before Spring Security authentication. |
| `ActuatorHealthEndpointTest` | `ledgerguard-api` | 5 | Actuator web endpoint exposure lockdown: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, and `/actuator/info` return 200 OK; `/actuator/metrics`, `/actuator/env`, and `/actuator/beans` return 404 Not Found. |
| `OutboxTraceContextIntegrationTest` | `ledgerguard-api` | 3 | V17 migration & DB trigger integrity: persisting outbox event with traceparent, tracestate, and correlation_id; database trigger `trg_fn_enforce_outbox_events_integrity` rejecting `UPDATE` to trace context fields (`IS DISTINCT FROM`); and check constraints bounding column lengths (traceparent $\le 128$, correlation_id $\le 64$). |
| `OutboxPublisherTracingIntegrationTest` | `ledgerguard-api` | 2 | Asynchronous Kafka publishing trace propagation: outbox publisher restores parent W3C context, injects `X-Correlation-Id`, propagates headers to Kafka, verifies fallback UUID when correlation_id is null, and proves 0 duplicate headers on published Kafka records. |
| `TraceCardinalityMetricsVerificationTest` | `ledgerguard-api` | 1 | Observability cardinality protection: verifies `/actuator/prometheus` contains zero trace ID, span ID, or correlation ID tags or labels, while Phase 29 business metrics remain fully functional. |
| `NotificationWorkerTracingIntegrationTest` | `notification-worker` | 2 | Worker consumer tracing continuation: consumer listener continues observation from inbound trace headers, extracts `X-Correlation-Id` into MDC, and handles malformed or missing headers gracefully without disrupting event processing. |
| `NotificationWorkerApplicationTests` | `notification-worker` | 3 | Worker application bootstrap & observation: bounded consumer backpressure properties, verification that Actuator `/metrics` and `/prometheus` are not exposed, and zero web server port binding. |

### Phase 30 Invariants Verified by Tests

1. **Header Sanitization & CRLF Injection Prevention**: `CorrelationIdFilterTest` and `CorrelationIdSecurityIntegrationTest` prove that all incoming `X-Correlation-Id` values are sanitized against `^[a-zA-Z0-9_-]{1,64}$`. Malformed headers, CRLF sequences, and oversized tokens are discarded and replaced with clean server UUIDs.
2. **Durable Outbox Trace Context & Immutability**: `OutboxTraceContextIntegrationTest` verifies that Flyway V17 schema captures traceparent, tracestate, and correlation_id durably in PostgreSQL `outbox_events`, and that trigger `trg_fn_enforce_outbox_events_integrity` prevents mutations on update.
3. **Kafka Trace Context Restoration & Header Deduplication**: `OutboxPublisherTracingIntegrationTest` verifies that `OutboxPublisherService` restores the W3C parent context from outbox records and deduplicates headers before calling `kafkaTemplate.send()`, guaranteeing zero duplicate `traceparent` or `X-Correlation-Id` headers.
4. **MDC Thread Isolation & Context Restoration**: Tests prove that `correlationId` is bound to MDC upon request entry and Kafka consumer invocation, and cleaned up / restored reliably in `finally` blocks, preventing thread leak across pooled worker threads.
5. **Zero Prometheus Metric Cardinality Inflation**: `TraceCardinalityMetricsVerificationTest` asserts that Prometheus scrape output contains zero high-cardinality trace or span labels, preserving collector stability.
6. **Non-Invasive Fail-Safe Telemetry Boundary**: Verifies that telemetry capture errors never fail financial transactions or cause database rollbacks.
7. **Database Trace Context Execution Without Child JDBC Spans**: Verifies that database operations executed as part of an observed HTTP request or observed Kafka listener invocation execute while that enclosing trace context is active. Phase 30 does not add individual JDBC query spans, @Transactional spans, or JDBC proxy dependencies. Flyway startup migrations and background/scheduled operations do not inherit request trace context. Zero SQL parameters, financial values, or query bodies are captured or emitted.
8. **Headless & Test Safe Telemetry Export**: Verifies that telemetry export is disabled by default (`management.tracing.export.otlp.enabled: false`), allowing headless test runs and local test suites to execute reliably without an external OpenTelemetry collector.
9. **Actuator Web Exposure Lockdown**: Verifies that `/actuator/metrics` is not exposed in `ledgerguard-api` (returns 404), while `/actuator/health`, `/actuator/info`, and `/actuator/prometheus` remain accessible and functional according to their Phase 29 contracts. Verifies that `notification-worker` is a non-web console service with zero web diagnostic endpoints exposed.

### Phase 30 Test Count

- `ledgerguard-api`: **675 tests, 0 failures, 0 errors, 0 skipped** (+24 tests from Phase 29 baseline of 651)
- `psp-simulator`: **17 tests, 0 failures, 0 errors, 0 skipped**
- `notification-worker`: **22 tests, 0 failures, 0 errors, 0 skipped** (+3 tests from Phase 29 baseline of 19)
- `failure-lab`: **1 test, 0 failures, 0 errors, 0 skipped**
- **Workspace total: 715 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-05).

---

## 12. Phase 31 — Grafana Operations & Financial Integrity Dashboards Verification

Phase 31 provisions containerized Prometheus v3.2.1 and Grafana v11.5.2 observability infrastructure. Because Phase 31 is strictly an infrastructure phase, zero Java code or database migrations were modified. Verification confirms infrastructure syntax, container lifecycle, Prometheus scraping, Grafana provisioning, and full test suite non-regression.

### Verification Matrix

| Area | Tool / Method | Target / Command | Verification Outcome |
| :--- | :--- | :--- | :--- |
| **Prometheus Config** | `promtool check config` | `/etc/prometheus/prometheus.yml` via `prom/prometheus:v3.2.1` | **SUCCESS**: Valid Prometheus configuration syntax. |
| **Dashboard JSON Syntax** | Node / PowerShell JSON Parser | `ledgerguard-financial-integrity.json`, `ledgerguard-api-operations.json` | **PASS**: Syntactically valid JSON, 0 parse errors. |
| **Docker Compose** | `docker compose config` | `docker-compose.yml` | **PASS**: Services `prometheus` and `grafana` validated with volumes and networking. |
| **Container Lifecycle** | Docker daemon | `docker compose up -d` | **PASS**: Containers start cleanly and pass health checks (`/-/healthy`, `/-/ready`, `/api/health`). |
| **Live Target Scrape** | Prometheus HTTP API | `GET /api/v1/targets` | **PASS**: Target `host.docker.internal:8080` reported with `health = "up"`. |
| **Custom Financial Metrics** | Prometheus HTTP API | `GET /api/v1/query?query=<metric>` | **PASS**: `unbalanced_journal_count`, `reconciliation_discrepancies`, `outbox_lag_seconds`, and `duplicate_idempotency_keys_total` return active vector series. |
| **PromQL Queries** | Prometheus HTTP API | 22 dashboard expressions via `/api/v1/query` | **PASS**: 20 valid with data, 2 valid but empty (5xx and 429 error rates during clean baseline), 0 invalid. |
| **Grafana Auth Security** | HTTP client | `GET /api/datasources` without auth | **PASS**: Returns 401 Unauthorized (anonymous access disabled). |
| **Grafana Provisioning** | Grafana HTTP API | Basic auth with `GRAFANA_ADMIN_PASSWORD` | **PASS**: Datasource `ledgerguard-prometheus` loaded as default; dashboards `ledgerguard-financial-integrity` and `ledgerguard-api-operations` loaded in folder `LedgerGuard`. |
| **Frontend Lint & Build** | npm | `npm run lint` & `npm run build` | **PASS**: 0 lint errors, production build succeeds, 0 frontend source diffs. |

### Phase 31 Invariants Verified

1. **Zero Financial Mutation**: Prometheus and Grafana operate as read-only telemetry components outside the financial transaction path. Any failure or restart cannot mutate ledger accounts or alter balances.
2. **Decoupled Scraping & Correct Semantics**: Financial integrity gauges reflect asynchronous in-memory sampling from Phase 29. Panel descriptions accurately reflect Phase 24 journal integrity rules and Phase 25 reconciliation discrepancy definitions.
3. **Cardinality & Aggregation Safety**: Average HTTP duration is computed via aggregate expressions (`sum(rate(sum))/clamp_min(sum(rate(count)), 1e-12)`), preventing series explosion or silent route partitioning. Zero high-cardinality IDs are exposed.
4. **Local Auth Hardening**: Anonymous viewer access is disabled; administrator password requires explicit developer configuration via `.env` without default fallback.

### Phase 31 Clean Verify Test Count

- `ledgerguard-api`: **675 tests, 0 failures, 0 errors, 0 skipped**
- `psp-simulator`: **17 tests, 0 failures, 0 errors, 0 skipped**
- `notification-worker`: **22 tests, 0 failures, 0 errors, 0 skipped**
- `failure-lab`: **1 test, 0 failures, 0 errors, 0 skipped**
- **Workspace total: 715 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-05).

---

## 13. Phase 32 — Money Integrity Failure Lab Backend Test Suite

Phase 32 introduces the programmatic chaos execution engine and mathematical financial verification suite in `backend/failure-lab`. It operates as a decoupled test harness executing against PostgreSQL via Testcontainers with Flyway V1–V17 migrations, enforcing strict environment safety (`EnvironmentGuard`), concurrency locking (`ConcurrencyGuard`), execution timeouts (30s), structured timeline events, and direct SQL financial invariant assertions (`FinancialInvariantOracle`).

### Test Class Registry

| Test Class | Module | Methods | Coverage Area |
| :--- | :--- | :---: | :--- |
| `EnvironmentGuardTest` | `failure-lab` | 9 | Safety gate unit tests: verifies deny-by-default positive authorization model (`LabDatabaseTarget`), ephemeral ownership token verification, and explicit rejection of non-local IP addresses (`10.0.0.8`), external hostnames (`db.internal`, `example.com`), and forbidden keywords (`prod`, `staging`, `live`, etc.). |
| `OpposingTransfersScenarioTest` | `failure-lab` | 1 | Scenario 1 real-system test: executes 2 concurrent opposing transfers (A->B and B->A) through real production `TransferService.createTransfer` under `CyclicBarrier(2)`; verifies deadlock-free completion, idempotency replay, debits == credits, and strict conservation of total money. |
| `TimeoutAfterCommitScenarioTest` | `failure-lab` | 1 | Scenario 2 real-system test: executes real `PayoutService.requestPayout` against `HttpProviderTestAdapter` configured for `TIMEOUT_AFTER_SUCCESS`; verifies payout enters `UNKNOWN` (`UNKNOWN != FAILED`), balance hold remains `ACTIVE`, and real `ProviderStatusPollingService` recovers state to `SUCCEEDED`, consumes hold, and posts exactly 1 balanced settlement journal. |
| `CorruptedSnapshotScenarioTest` | `failure-lab` | 1 | Scenario 3 real-system test: deliberately injects snapshot drift via `SnapshotFaultInjector` under `LabDatabaseTarget`; executes real Phase 24 `SnapshotConsistencyChecker` to detect `SNAPSHOT_MISMATCH` discrepancy item; executes real Phase 25 `SnapshotAutoRepairService` to auto-repair snapshot from immutable journals under `FOR UPDATE` lock. |
| `WebhookRaceScenarioTest` | `failure-lab` | 1 | Scenario 4 real-system test: dispatches 5 concurrent duplicate signed HMAC-SHA256 webhooks to real `ProviderWebhookController.receiveWebhook`; verifies real HMAC validation, provider event deduplication on `(provider_id, provider_event_id)` accepting 1 and deduplicating 4, and strictly single settlement journal posting. |
| `FailureLabSuiteRunnerTest` | `failure-lab` | 1 | End-to-end suite runner integration test: registers and executes all 4 real chaos scenarios sequentially through `ScenarioRunner` and `ScenarioRegistry`; validates scenario execution reports, timeline step events, and clean invariant audits. |

### Phase 32 Invariants Verified by Tests

1. **Real-System Execution vs Fake JDBC Simulation**: All chaos scenarios exercise real LedgerGuard Spring application services (`TransferService`, `PayoutService`, `ProviderStatusPollingService`, `SnapshotConsistencyChecker`, `SnapshotAutoRepairService`, `ProviderWebhookController`), providing genuine proof of production behavior under concurrency and faults.
2. **Deterministic Lock Ordering Eliminates Circular-Wait Deadlocks**: Opposing transfers executed concurrently across threads complete without deadlock when accounts are locked ordered by `ledger_account_id ASC`.
3. **Ambiguous Outcome Safety (`UNKNOWN != FAILED`)**: A transport timeout following a provider commit leaves the transaction in `UNKNOWN` and preserves the balance hold as `ACTIVE`, preventing premature release or double-spending until authoritative status resolution.
4. **Journal Immutability & Snapshot Auto-Repair**: Direct balance snapshot drift does not alter immutable historical journal entries; Level 2 reconciliation detects the discrepancy, and dynamic reconstruction from `POSTED` journals restores mathematical accuracy.
5. **Webhook Deduplication & Single Economic Effect**: Concurrent identical webhooks are deduplicated at the database boundary via unique constraints, guaranteeing at most one state transition and exactly one financial settlement journal.
6. **Fail-Closed Environment Safety with Positive Authorization**: `EnvironmentGuard` requires explicit ephemeral target authorization via `LabDatabaseTarget` and rejects any non-local or production/staging target.

### Phase 32 Verified Test Count

- `ledgerguard-api`: **675 tests, 0 failures, 0 errors, 0 skipped**
- `psp-simulator`: **17 tests, 0 failures, 0 errors, 0 skipped**
- `notification-worker`: **22 tests, 0 failures, 0 errors, 0 skipped**
- `failure-lab`: **15 tests, 0 failures, 0 errors, 0 skipped** (+14 net tests, placeholder test deleted)
- **Workspace total: 729 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-06).

---

## 18. Phase 33 — Failure Lab Frontend & Interactive Invariant Visualizer

### Phase 33 Test Registry

| Test Class | Module | Tests | Description |
| :--- | :--- | :--- | :--- |
| `FailureLabControllerTest` | `failure-lab` | 14 | REST API integration tests: validates `GET /api/lab/environment` (200 enabled vs 503 disabled), `GET /api/lab/scenarios` (all 4 scenarios with metadata), `POST /api/lab/runs` (202 Accepted launch vs 409 Conflict when locked vs 400 validation), `GET /api/lab/runs/active` (200 when active vs 204 when idle), `GET /api/lab/runs/{runId}` (200 found vs 404 missing), `GET /api/lab/runs?limit=10` (bounded history), limit validation (400 on <= 0), and CORS headers (allowed origin vs 403 on disallowed origin). |
| `LabRunCoordinatorTest` | `failure-lab` | 5 | Asynchronous coordinator engine tests: verifies single active run concurrency enforcement, atomic admission (202 vs 409 under concurrency), timeout overlap prevention (retains exclusive guard while worker thread is alive after timeout, permits new run after worker exits), live timeline streaming, and bounded history storage. |
| `EnvironmentGuardTest` | `failure-lab` | 9 | Preserved from Phase 32: Positive authorization and fail-closed environment safety tests. |
| `OpposingTransfersScenarioTest` | `failure-lab` | 1 | Preserved from Phase 32: Scenario 1 Opposing Transfers real execution. |
| `TimeoutAfterCommitScenarioTest` | `failure-lab` | 1 | Preserved from Phase 32: Scenario 2 Timeout After Commit real execution. |
| `CorruptedSnapshotScenarioTest` | `failure-lab` | 1 | Preserved from Phase 32: Scenario 3 Corrupted Snapshot real execution. |
| `WebhookRaceScenarioTest` | `failure-lab` | 1 | Preserved from Phase 32: Scenario 4 Webhook Race real execution. |
| `FailureLabSuiteRunnerTest` | `failure-lab` | 1 | Preserved from Phase 32: Sequential suite runner across all 4 scenarios. |
| `ScenarioRunnerConcurrencyTest` | `failure-lab` | 1 | Preserved from Phase 32: Concurrency lock acquisition and release bounds. |

### Frontend Build & Typecheck Verification
- `npm run lint`: **PASS** (0 warnings, 0 errors)
- `npm run build`: **PASS** (TypeScript 5.7.3 compilation + Vite bundle generated clean in 434ms)

### Phase 33 Verified Test Count

- `ledgerguard-api`: **675 tests, 0 failures, 0 errors, 0 skipped**
- `psp-simulator`: **17 tests, 0 failures, 0 errors, 0 skipped**
- `notification-worker`: **22 tests, 0 failures, 0 errors, 0 skipped**
- `failure-lab`: **34 tests, 0 failures, 0 errors, 0 skipped** (+19 tests)
- **Workspace total: 748 tests, 0 failures, 0 errors, 0 skipped**

Verified by `.\mvnw.cmd clean verify` (2026-09-06).

### Manual Browser Walkthrough Procedures

1. **Start Failure Lab Executable Backend**:
   ```powershell
   .\mvnw.cmd -pl backend/failure-lab spring-boot:run "-Dspring-boot.run.arguments=--ledgerguard.lab.enabled=true"
   ```
   Confirm backend starts on `127.0.0.1:8083` with ephemeral PostgreSQL 17.11 and Kafka 4.3.1 Testcontainers.
2. **Start Frontend Development Server**:
   ```powershell
   cd frontend/ledgerguard-web
   npm run dev
   ```
3. **Log in as Operations Engineer**:
   Navigate to `http://localhost:5173/login`, authenticate with `ops@ledgerguard.com` / valid password.
4. **Inspect Role-Based Navigation**:
   Verify the "Failure Lab" button (with `ScienceIcon`) is present in the top navigation bar and in the user menu.
5. **Role Guard Verification**:
   Log out, log in as Customer (`customer@ledgerguard.com`), navigate to `http://localhost:5173/app/failure-lab`. Verify immediate redirection to `/app` with no chaos controls exposed.
6. **Access Failure Lab Console (as OPS)**:
   Navigate to `http://localhost:5173/app/failure-lab`.
7. **Verify Safety Disclaimer & Environment Status**:
   - Confirm prominent banner: `EPHEMERAL LAB ENVIRONMENT (ISOLATED)`.
   - Verify status chips: `PostgreSQL 17.11: UP`, `Kafka 4.3.1: UP`, `Mock PSP Adapter: UP`.
8. **Verify Scenario Grid**:
   Confirm all 4 scenarios are displayed with their respective category chips, fault mechanisms, and invariant contracts:
   - Opposing Concurrent Transfers (`CONCURRENCY`)
   - Payout Timeout After Success (`DISTRIBUTED_FAULT`)
   - Snapshot Balance Drift & Auto-Repair (`DATA_INTEGRITY`)
   - Concurrent Duplicate Webhooks Race (`IDEMPOTENCY`)
9. **Execute Scenario 1 (Opposing Transfers)**:
   - Click "Execute Chaos Run" on Scenario 1.
   - Observe immediate transition to `RUNNING` status and elapsed timer start.
   - Watch live timeline step events arrive: `SETUP` $\to$ `DISPATCH` $\to$ `COMPLETED` $\to$ `IDEMPOTENCY_VERIFIED` $\to$ `ORACLE_AUDIT`.
   - Observe terminal status `PASSED`.
   - Switch to "Mathematical Invariants" tab and confirm:
     - Journal Integrity: $\sum \text{Debit} = \sum \text{Credit}$ (Difference $= 0$) $\to$ `VERIFIED`
     - Snapshot Parity: $\text{Snapshot} = \sum \text{Posted Journals}$ $\to$ `VERIFIED`
     - Available Balance: $\text{Available} \ge 0$ $\to$ `VERIFIED`
     - Internal Transfer Conservation: $\Delta A + \Delta B = 0$ $\to$ `VERIFIED`
10. **Execute Scenario 2 (Timeout After Commit)**:
    - Click "Execute Chaos Run" on Scenario 2.
    - Observe timeline steps: `INJECT_FAULT` (sets adapter to `TIMEOUT_AFTER_SUCCESS`), `AMBIGUITY_CONFIRMED` (`UNKNOWN` status, hold `ACTIVE`, 0 settlement journals), `TRIGGER_RECOVERY` (poller sweep), `SETTLED` (`SUCCEEDED`, hold `CONSUMED`), `ORACLE_AUDIT`.
    - Confirm Single Economic Effect invariant: `Settlement Journal Count = 1` $\to$ `VERIFIED`.
11. **Execute Scenario 3 (Corrupted Snapshot)**:
    - Click "Execute Chaos Run" on Scenario 3.
    - Observe `INJECT_DRIFT` (authorized mutation under `LabDatabaseTarget`), `RUN_DETECTION` (Level 2 reconciliation detects `SNAPSHOT_MISMATCH`), `RUN_REPAIR` (auto-repair dynamic reconstruction from immutable journals), `REPAIRED`, `ORACLE_AUDIT`.
    - Confirm Snapshot Parity restored to immutable journal truth.
12. **Execute Scenario 4 (Webhook Race)**:
    - Click "Execute Chaos Run" on Scenario 4.
    - Observe `DISPATCH_RACE` (5 concurrent signed webhooks via `CyclicBarrier`), `RACE_SETTLED` (1 accepted, 4 deduplicated), `ORACLE_AUDIT`.
    - Confirm Single Economic Effect ($\le 1$ journal) and zero duplicate payouts.
13. **Verify Concurrency Guard (409 Conflict UX)**:
    - Launch Scenario 1 and immediately click "Execute Chaos Run" on Scenario 2.
    - Confirm rejection banner: "Another scenario is currently executing. Max 1 active run permitted."
14. **Verify Run History Table**:
    - Observe all completed runs listed with their Run ID, Scenario, Duration, Status, and timestamp.
    - Click "Inspect" on an earlier run and verify the active run console updates to display that run's timeline and invariant results.
15. **Verify Page Refresh State Recovery**:
    - Launch a scenario and refresh the browser mid-execution.
    - Confirm the UI automatically queries `GET /api/lab/runs/active` and resumes live polling and progress display seamlessly.
16. **Verify Offline Backend State**:
    - Stop the `FailureLabApplication` process.
    - Refresh the browser.
    - Confirm the UI displays the graceful offline alert banner with instructions on how to start the backend with `.\mvnw.cmd -pl backend/failure-lab spring-boot:run "-Dspring-boot.run.arguments=--ledgerguard.lab.enabled=true"`.

---

## 19. Phase 34 - Complete Testcontainers & End-to-End Suite

Phase 34 unifies all integration, database, messaging, and multi-service flows in a Testcontainers E2E test suite running against real packaged fat JARs (`ledgerguard-api`, `psp-simulator`, `notification-worker`) inside real JVM containers (`eclipse-temurin:21-jre`) on a private virtual bridge network with PostgreSQL 17.11 and Kafka 4.3.1.

### Test Class Registry

All tests are located in `backend/e2e-tests/src/test/java/com/ledgerguard/e2e/flows/`:

| Test Class | Method / Test Case | Purpose & Flow Verified | Invariants Checked |
|---|---|---|---|
| `PlatformStartupIT` | `testDatabaseConnectionAndMigrations` | Verifies PostgreSQL 17.11 container health and Flyway migrations V1–V17 applied cleanly across isolated logical databases. | Flyway migration state, zero missing tables |
| | `testLedgerGuardApiActuatorHealth` | Validates `ledgerguard-api` web container readiness and `/actuator/health` status `UP`. | Health indicator contract |
| | `testPspSimulatorReadiness` | Validates `psp-simulator` web container readiness via provider HTTP operations boundary. | Provider HTTP availability |
| | `testNotificationWorkerStartupAndRunning` | Validates `notification-worker` asynchronous JVM startup and running process state. | Container running lifecycle |
| `AuthenticationAndWalletIT` | `testCustomerRegistrationLoginAndWalletCreation` | Registers a customer, logs in to obtain JWT token, creates INR wallet, and validates account snapshot. | Auth RBAC, wallet initialization, initial snapshot balance = 0 |
| | `testDuplicateRegistrationFails` | Verifies duplicate customer email registration returns HTTP 400 Bad Request. | Uniqueness constraints |
| `ExternalWalletFundingIT` | `testExternalFundingSuccessAndIdempotency` | End-to-end funding request through API -> PSP Simulator -> HTTP webhook callback -> Outbox settlement. Submits duplicate idempotency key to verify replay immunity. | Single economic effect, balance credited, idempotency replay = same response |
| `ExternalPayoutAndHoldIT` | `testExternalPayoutHoldReservationAndSettlement` | Tests external withdrawal: wallet creation, funding, payout initiation (creates `ACTIVE` hold), PSP dispatch, and async settlement (`CONSUMED` hold + double-entry journal). | Available balance invariant ($A \ge 0$), hold state transitions, single economic effect |
| `InternalTransferIT` | `testInternalTransferBetweenWallets` | Executes internal transfer between two customer wallets under JWT authentication and asserts balance conservation. | Zero-sum transfer conservation ($\Delta A + \Delta B = 0$), double-entry journal balance ($\sum D = \sum C$) |
| `MerchantPaymentAndRefundIT` | `testMerchantPaymentAndFullRefund` | Validates merchant checkout authorization and subsequent full refund through API -> Outbox -> Kafka. | Double-entry journal balance, available balance invariants, outbox event generation |
| `MessagingNotificationIT` | `testOutboxToKafkaNotificationPipeline` | Asserts outbox event generation in PostgreSQL, publisher poller emission to Kafka, and notification worker consumption. | At-least-once message delivery, outbox status transitions (`SENT`), Kafka message propagation |

> **Note on Ambiguous Outcome Recovery**: Adversarial transport timeouts and ambiguous provider outcomes (`TIMEOUT_AFTER_COMMIT`, `UNKNOWN` payout status, active hold preservation, and poller recovery) are authoritatively covered by the Failure Lab (`backend/failure-lab`). The Phase 34 E2E suite focuses strictly on non-adversarial cross-service packaged application workflows without synthetic wildcard intercepts or webhook suppression.

### Container Topology & Network Isolation

- **Virtual Bridge Network**: All 5 services (`postgres`, `kafka`, `psp-simulator`, `notification-worker`, `ledgerguard-api`) run inside an isolated Testcontainers network.
- **Real Packaged Fat JARs**: Built with Spring Boot repackage plugin targeting `eclipse-temurin:21-jre`.
- **Database Isolation**: Single PostgreSQL container hosting 3 isolated logical databases (`ledgerguard`, `psp_simulator`, `notification_worker`).
- **Internal Kafka Listener**: Ephemeral broker configured with advertised internal listener on private network alias `kafka:19092`.
- **Inter-Container HTTP Webhook**: Real HTTP callback from `psp-simulator` to `http://ledgerguard-api:8080/api/provider/webhooks`.
- **Real Provider Contract**: Fixed wire contract (`boolean replayed`) on PSP Simulator; zero javaagents or Jackson runtime tampering.
- **Deterministic JAR Resolution**: `JarResolver` validates existence, file size (> 1 MB), and `Main-Class` manifest attributes.
- **Database Probe**: `E2EDatabaseProbe` executes read-only SQL queries to assert double-entry ledger balance, snapshot parity, hold states, and outbox event states.

### Phase 34 Clean Verify Test Count

- **LedgerGuard Parent**: Success
- **LedgerGuard API**: 675 tests (0 failures, 0 errors, 0 skipped)
- **PSP Simulator**: 18 tests (0 failures, 0 errors, 0 skipped)
- **Notification Worker**: 22 tests (0 failures, 0 errors, 0 skipped)
- **Failure Lab**: 34 tests (0 failures, 0 errors, 0 skipped)
- **E2E Tests**: 11 tests (0 failures, 0 errors, 0 skipped)
- **Workspace Total**: **760 tests** (0 failures, 0 errors, 0 skipped)
