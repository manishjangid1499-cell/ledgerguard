package com.ledgerguard.reconciliation.domain;

/**
 * Specific problem type of a reconciliation item.
 * <p>
 * Journal level: UNBALANCED_JOURNAL, MALFORMED_JOURNAL<br>
 * Snapshot level: SNAPSHOT_MISMATCH, SNAPSHOT_MISSING<br>
 * Provider level: PROVIDER_STATUS_MISMATCH, PROVIDER_IDENTITY_MISMATCH,
 *                 PROVIDER_NOT_FOUND, PROVIDER_UNAVAILABLE, PROVIDER_STILL_PROCESSING
 */
public enum ReconciliationProblemType {
    // Level 1 — Journal Balance
    UNBALANCED_JOURNAL,
    MALFORMED_JOURNAL,

    // Level 2 — Snapshot Consistency
    SNAPSHOT_MISMATCH,
    SNAPSHOT_MISSING,

    // Level 3 — Provider Settlement
    PROVIDER_STATUS_MISMATCH,
    PROVIDER_IDENTITY_MISMATCH,
    PROVIDER_NOT_FOUND,
    PROVIDER_UNAVAILABLE,
    PROVIDER_STILL_PROCESSING
}
