# ADR-009: Three-Tier Reconciliation Architecture

## Status
Accepted

## Context
Even in systems with transactional guarantees and idempotent APIs, distributed discrepancies can emerge over time due to external bank settlement errors, network drops during callback delivery, software defects, or manual operator interventions.

A production-grade financial platform cannot assume that operational state is forever infallible. It must implement active, automated reconciliation mechanisms to continuously audit, detect, and resolve financial discrepancies.

## Decision
We implement a comprehensive **Three-Tier Reconciliation Engine**:

```
+-------------------------------------------------------------------------+
| Level 1: Journal Invariant Reconciliation (Double-Entry Balance Audit)  |
| Rule: Across all posted transactions, SUM(DEBITS) == SUM(CREDITS).      |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Level 2: Balance Snapshot Reconciliation (Snapshot vs. Ledger Sum)      |
| Rule: For every account, account_balances snapshot == SUM(journal_entries)|
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
| Level 3: External Provider Reconciliation (Internal Ledger vs. PSP State)|
| Rule: Internal operations match external settlement reports from PSP.   |
+-------------------------------------------------------------------------+
```

1. **Tier 1 — Journal Invariant Reconciliation**:
   - Executes periodic scans across `journal_transactions` and `journal_entries` to verify that no unbalanced transactions exist.
   - Any unbalanced transaction triggers immediate critical alerts.
2. **Tier 2 — Balance Snapshot Reconciliation**:
   - Recomputes the posted balance of every account by aggregating all immutable journal entries from the beginning of time (or audited checkpoint).
   - Compares the aggregated balance against the active `account_balances` snapshot.
   - **Auto-Repair Capability**: If a mismatch is detected, the engine can automatically re-align the snapshot with the immutable ledger history and record an audit log.
3. **Tier 3 — External Provider Reconciliation**:
   - Ingests daily/hourly settlement files or query dumps from the external PSP Simulator.
   - Compares external transaction status, amount, and reference IDs against internal `payments`, `funding_operations`, and `payouts`.
   - Classifies discrepancies (`MATCH`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH`, `MISSING_INTERNAL`, `MISSING_EXTERNAL`).
   - Automatically settles pending `UNKNOWN` operations where provider success is verified; routes ambiguous mismatches to the `MANUAL_REVIEW` operational queue.

## Alternatives Considered
1. **Manual Ad-Hoc SQL Queries by Operators**:
   - *Rejected*: Inefficient, error-prone, lacks auditability, and fails to catch drift proactively before end-users are impacted.
2. **Single-Level Reconciliation (External Only)**:
   - *Rejected*: Misses internal ledger corruption or snapshot desynchronization.

## Consequences
- **Positive**:
  - Automated detection of internal and external financial drift.
  - Safe, auditable auto-repair for read-snapshot inconsistencies based on immutable ledger truth.
  - Eliminates silent losses from lost external callbacks.
- **Negative**:
  - Batch reconciliation queries can introduce database load (mitigated by read-only query optimization, off-peak scheduling, or running on read replicas).

## Trade-offs
We accept the computational cost of continuous reconciliation scans in exchange for mathematical certainty and automated self-healing of financial snapshots.
