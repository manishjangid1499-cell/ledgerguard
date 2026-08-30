# ADR-003: Immutable Double-Entry Financial Ledger

## Status
Accepted

## Context
In basic CRUD wallet applications, account balances are often stored as a single mutable integer or decimal column in an `accounts` table. Balance changes are performed via `UPDATE accounts SET balance = balance + amount`.

This approach is unacceptable for a production-grade financial platform:
1. It destroys financial history and auditability; it is impossible to verify how a balance reached its current state.
2. It is vulnerable to silent corruption, lost updates, and untraceable modifications.
3. It cannot guarantee that money was neither created nor destroyed during complex multi-party flows.

## Decision
We adopt an **Immutable Double-Entry Accounting Model** as the foundational core of LedgerGuard.

- Financial state is recorded as append-only **`journal_transactions`** and **`journal_entries`**.
- Every journal transaction must contain two or more entries satisfying the fundamental equation:
  $$\sum \text{Debits} = \sum \text{Credits}$$
- Once inserted, rows in `journal_transactions` and `journal_entries` are **strictly immutable**. `UPDATE` and `DELETE` operations on these tables are forbidden by database permissions and application logic.
- Corrections, cancellations, or reversals must be executed by creating new compensating journal transactions.
- Account balances (`account_balances`) serve purely as read-optimized snapshots, always reconstructible from the immutable ledger.

## Alternatives Considered
1. **Single-Entry Mutable Balances**:
   - *Rejected*: Lacks auditability, prevents mathematical proof of money conservation, and risks silent balance corruption.
2. **Event Sourced Entity State across all domain tables**:
   - *Rejected*: Increases complexity across non-financial entities (e.g., users, profiles). The double-entry ledger already acts as the specialized, mathematically proven event log for financial state.

## Consequences
- **Positive**:
  - Complete, tamper-evident audit trail of all financial movements.
  - Invariant verification: automated systems can mathematically prove that total platform debits equal credits at all times.
  - Ability to reconstruct historical balances at any exact point in time.
- **Negative**:
  - Higher database storage footprint due to append-only growth (managed by database partitioning and snapshot checkpoints in high-volume environments).
  - Requires balanced debit/credit design for every financial operation.

## Trade-offs
We accept increased storage requirements and transaction authoring discipline in exchange for total auditability, tamper resistance, and mathematical integrity.
