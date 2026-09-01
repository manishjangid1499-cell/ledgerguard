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
