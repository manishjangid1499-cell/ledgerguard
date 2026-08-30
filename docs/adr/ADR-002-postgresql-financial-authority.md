# ADR-002: PostgreSQL as Authoritative Financial Store

## Status
Accepted

## Context
Financial systems require absolute data integrity, linearizable transactional boundaries, durable persistence, rich constraint enforcement (foreign keys, uniqueness, check constraints), and predictable concurrency control (pessimistic row locking, partial indexing). 

Some modern designs attempt to use NoSQL databases (e.g., MongoDB, Cassandra) or distributed key-value stores for ledger state to claim horizontal write scaling. However, these systems often sacrifice multi-table transactional invariants, strict constraint enforcement, and immediate consistency across multiple accounts.

## Decision
We establish **PostgreSQL** as the single authoritative source of truth for all financial state, ledger transactions, balance snapshots, idempotency records, and outbox queues in LedgerGuard.

- Schema changes must be explicitly versioned and executed using **Flyway**.
- Hibernate / JPA must be configured with `ddl-auto=validate` in production/staging and never allowed to automatically alter table definitions (`ddl-auto=update` is prohibited).
- Concurrency correctness will rely on PostgreSQL's row-level locks (`SELECT ... FOR UPDATE`), transaction isolation levels, and ACID guarantees.

## Alternatives Considered
1. **NoSQL Document Database (MongoDB)**:
   - *Rejected*: Lacks strong multi-table foreign-key relational integrity; multi-document transactions carry higher overhead and weaker concurrency tuning for double-entry ledgering.
2. **Distributed Wide-Column Store (Cassandra)**:
   - *Rejected*: Eventual consistency and lack of ACID transactions across multiple partitions make strict double-entry balance enforcement ($\sum \text{Debits} = \sum \text{Credits}$) and concurrent balance locking impossible without heavy external distributed coordinators.

## Consequences
- **Positive**:
  - Unmatched relational integrity with foreign keys, check constraints, and unique constraints enforcing financial correctness at the database engine level.
  - Granular concurrency control via `FOR UPDATE` and non-blocking queue claiming via `FOR UPDATE SKIP LOCKED`.
  - Rich SQL querying for multi-dimensional reconciliation and invariant verification.
  - Mature tooling, backup, point-in-time recovery (PITR), and replication ecosystem.
- **Negative**:
  - Vertical scaling ceiling before requiring connection pooling tuning, read replicas, or future partition strategies.

## Trade-offs
We trade theoretical infinite distributed NoSQL write throughput for absolute transactional correctness, relational safety, and immediate consistency.
