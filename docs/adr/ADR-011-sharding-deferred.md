# ADR-011: Database Sharding Intentionally Deferred

## Status
Accepted

## Context
When architecting financial ledgers, engineers frequently contemplate database sharding (horizontal database partitioning by account ID or tenant ID) to handle massive transaction volumes.

However, sharding introduces immense architectural complexity into a financial core:
1. **Cross-Shard Transactions**: An internal transfer between Customer A (on Shard 1) and Customer B (on Shard 2) loses single-database ACID guarantees. It requires distributed two-phase commit (2PC) or multi-phase distributed sagas with clearing accounts.
2. **Hot Account Contention**: System-wide platform reserve or fee accounts become cross-shard bottlenecks regardless of partitioning scheme.
3. **Reconciliation & Reporting**: Global consistency queries ($\sum \text{Debits} == \sum \text{Credits}$) require distributed scatter-gather queries across all database shards.

In the initial and medium stages of a financial platform processing thousands of transactions per second (TPS), a single well-tuned PostgreSQL instance with proper connection pooling (HikariCP), NVMe SSD storage, and efficient B-Tree indexing is more than sufficient.

## Decision
We **intentionally defer database sharding** in LedgerGuard.

- The system operates against a single authoritative PostgreSQL database instance for financial state.
- Scalability is achieved by:
  1. Horizontal scaling of stateless `ledgerguard-api` application nodes.
  2. Read-optimized `account_balances` snapshot caching to minimize table scans.
  3. Non-blocking outbox processing via `FOR UPDATE SKIP LOCKED`.
  4. Offloading asynchronous side effects (notifications, external polling) to Kafka and background workers.
- A future architectural document may evaluate account-based sharding and cross-shard settlement protocols when scale benchmarks require it.

## Alternatives Considered
1. **Early Database Sharding by Account ID**:
   - *Rejected*: Premature optimization that destroys local ACID transactions for peer-to-peer transfers and exponentially increases failure modes.
2. **Citus / Distributed PostgreSQL**:
   - *Rejected*: Adds complex distributed coordinator dependencies and query restrictions without solving the fundamental distributed commit problem for cross-node money movement.

## Consequences
- **Positive**:
  - Preserves simple, guaranteed ACID transactions for all internal peer-to-peer transfers.
  - Zero cross-shard coordinator deadlocks or distributed two-phase commit failures.
  - Extremely simple backup, restore, point-in-time recovery, and local development workflows.
- **Negative**:
  - Imposes an upper ceiling on single-database write throughput (though typically $>5,000\text{ TPS}$ on modern hardware, far exceeding demonstration requirements).

## Trade-offs
We trade theoretical multi-terabyte horizontal sharding capacity for immediate transactional simplicity, rock-solid correctness, and zero distributed transaction overhead.
