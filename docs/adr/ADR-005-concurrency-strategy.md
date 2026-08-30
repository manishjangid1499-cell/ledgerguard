# ADR-005: Concurrency Strategy & Deterministic Locking

## Status
Accepted

## Context
Under concurrent workloads (such as simultaneous peer transfers, concurrent card authorizations, or withdrawal races), financial platforms face two critical concurrency hazards:
1. **Double-Spending / Overdraft Races**: Two concurrent transactions read an available balance of ₹100, both authorize a ₹100 spend, and both commit, leaving the account at -₹100.
2. **Deadlocks in Opposing Transfers**: Transaction 1 transfers from Account A to Account B (locking A then B), while Transaction 2 transfers from Account B to Account A (locking B then A). When executed concurrently, both transactions block on each other, causing PostgreSQL to detect a circular wait and abort one with a deadlock exception.

Relying on single-JVM locks (`synchronized` or Java `ReentrantLock`) fails when multiple application instances run horizontally behind a load balancer.

## Decision
We implement **Database-Enforced Concurrency Control with Deterministic Lock Ordering**:

1. **Pessimistic Row Locking**:
   - Balance checks and updates acquire exclusive row locks using `SELECT ... FOR UPDATE` on `account_balances` inside the database transaction.
2. **Deterministic Lock Ordering**:
   - For all multi-account operations (such as internal transfers), all code paths must acquire account locks in a single deterministic global order, such as ascending lexicographical or numerical order of account IDs:
     ```
     Lock Order = min(sender_id, recipient_id) -> max(sender_id, recipient_id)
     ```
   - This mandatory global order prevents the circular-wait pattern caused by opposing transfers acquiring the same account locks in opposite sequences.
3. **Database Deadlock & Transient Serialization Awareness**:
   - Deterministic lock ordering specifically targets opposing-transfer circular waits; it does not guarantee that PostgreSQL can never detect a deadlock or serialization failure from other complex concurrent lock interactions or index scans.
   - The application must be prepared to handle transient PostgreSQL deadlock/serialization errors gracefully and may employ carefully bounded retries only when the entire operation is safe and idempotent to retry.
4. **Database Check Constraints**:
   - The `account_balances` table includes a `CHECK (available_balance >= 0)` constraint for wallet accounts, ensuring that even in the presence of software defects, the database rejects any transaction resulting in an illegal negative spendable balance.
5. **No JVM-Only Concurrency Locks**:
   - Concurrency safety is entirely delegated to PostgreSQL, allowing stateless horizontal scaling of `ledgerguard-api` instances.

## Alternatives Considered
1. **Optimistic Locking (`@Version` column)**:
   - *Rejected for High-Contention Paths*: Under high transfer volume or hot merchant accounts, optimistic locking results in frequent `OptimisticLockException` rollbacks, degrading user experience and requiring aggressive retry loops.
2. **Distributed Redis Locks (Redlock)**:
   - *Rejected*: Introduces an external infrastructure dependency, clock drift sensitivity, and non-atomic failure modes between Redis lock release and PostgreSQL transaction commit.
3. **JVM-level `synchronized` Blocks**:
   - *Rejected*: Ineffective across multi-node deployments and vulnerable to JVM restarts.

## Consequences
- **Positive**:
  - Reliable protection against overdraft races and double-spending across multiple API nodes.
  - Prevents circular-wait deadlocks on opposing peer-to-peer transfers.
  - Fail-safe protection via PostgreSQL `CHECK` constraints.
  - Clear architectural strategy for handling transient database serialization/deadlock errors with bounded retries.
- **Negative**:
  - Slightly higher lock hold duration on individual account rows during transaction execution (mitigated by keeping database transactions minimal and fast).

## Trade-offs
We accept brief row-level lock serialization on actively transacting accounts in exchange for resilient, multi-node financial correctness and protection against opposing-transfer lock contention.
