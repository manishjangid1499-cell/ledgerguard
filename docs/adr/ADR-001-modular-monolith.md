# ADR-001: Keep Financially Coupled Operations in a Modular Monolith

## Status
Accepted

## Context
In financial systems, core money operations (such as internal transfers, wallet payments, fee deductions, and hold management) involve multiple interdependent actions: checking account balances, locking debtor and creditor rows, generating debit/credit ledger records, updating balance snapshots, and inserting outbox events. 

In a distributed microservice architecture (e.g., splitting into `Wallet Service`, `Payment Service`, and `Ledger Service`), this transaction boundary becomes distributed across network boundaries. Distributed transactions require either Two-Phase Commit (2PC)—which is fragile, slow, and prone to coordinator blocking—or Saga choreographies/orchestrations, which introduce complex compensating transactions, temporary inconsistencies, and race windows where money can be double-spent before a compensating step completes.

## Decision
We will build the core financial platform as a **Modular Monolith** (`ledgerguard-api`). 

All core financial domains (`account`, `ledger`, `transfer`, `payment`, `refund`, `hold`, `idempotency`, `outbox`, `reconciliation`) will reside inside a single Spring Boot application deployable. Cross-domain interactions within the financial core will be executed as in-memory method invocations inside a single local PostgreSQL ACID transaction. 

External boundaries that are physically asynchronous (e.g., external banking integration via `psp-simulator` and decoupled notifications via `notification-worker`) will remain separate deployables communicating via REST and Kafka respectively.

## Alternatives Considered
1. **Microservices for Core Domains (`Wallet`, `Payment`, `Ledger`)**:
   - *Rejected*: Creates unnecessary distributed transactions, network latency, and eventual consistency windows on fundamental balance movements where strict immediate consistency is required.
2. **Event Sourced Distributed Services**:
   - *Rejected*: Introduces significant operational complexity and makes transactional overdraft prevention (available balance checks) under high concurrency difficult without complex distributed locking.

## Consequences
- **Positive**:
  - Full ACID transaction guarantees: atomic commit or rollback across account balances, journal entries, business records, and outbox rows.
  - Zero risk of partial balance updates or ghost journal entries from network timeouts between internal services.
  - Simplified local development, deterministic debugging, and lower operational overhead.
  - High performance by avoiding inter-service RPC serialization overhead during money movement.
- **Negative**:
  - Requires strict internal module boundaries and code discipline to prevent spaghetti dependencies between packages.
  - Monolith must be scaled as a single unit (mitigated by stateless application design).

## Trade-offs
We accept deploying a single backend application artifact in exchange for rock-solid transactional correctness and eliminating distributed transaction failure modes from the core money path.
