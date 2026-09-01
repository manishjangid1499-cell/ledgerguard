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
  - **Outbox Poller with `SKIP LOCKED`**: Multiple worker instances claiming distinct pending events without duplicate processing (Phase 17).
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
