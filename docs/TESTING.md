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
  - **Opposing Transfers**: Simultaneous transfers between Account A and Account B across 50+ concurrent threads to verify prevention of opposing-transfer circular waits via deterministic lock ordering.
  - **Concurrent Overdraft Race**: 20 concurrent threads attempting to spend ₹100 from an account with only ₹100 available balance. Proves exactly one thread succeeds and 19 fail with insufficient funds.
  - **Concurrent Refunds**: Multiple threads attempting simultaneous partial refunds against a single payment, verifying that total refunds never exceed the original payment.

### Layer 3: Integration Tests (Testcontainers)
- **Scope**: Testing database repositories, Spring Data JPA mappings, and messaging pipelines.
- **Targets**:
  - **Idempotency Races**: Multi-threaded concurrent executions with identical `(actor_user_id, operation, idempotency_key, fingerprint)` verifying that exactly 1 underlying operation executes and duplicates receive replayed cached results; concurrent conflicting fingerprints reject losers without duplicate execution.
  - **Idempotency Immutability & Rollback**: Direct JDBC tests verifying trigger rejection of direct `COMPLETED` inserts, metadata updates, status reversals, and deletions; operation rollback cleanly rolls back uncommitted `IN_PROGRESS` claims allowing retry.
  - **Transactional Outbox**: Validating that database rollbacks drop outbox rows, and committed transactions persist events in `PENDING` state.
  - **Outbox Poller with `SKIP LOCKED`**: Multiple worker instances claiming distinct pending events without duplicate processing.
  - **Kafka Consumer Inbox**: Redelivery of duplicate Kafka messages asserts zero duplicate domain side-effects.

### Layer 4: Webhook & Security Integration Tests
- **Scope**: HTTP layer security, role-based authorization, and signature validation.
- **Targets**:
  - Verification that unauthenticated requests return HTTP 401.
  - Verification that a `CUSTOMER` cannot access another customer's wallet (HTTP 403).
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
