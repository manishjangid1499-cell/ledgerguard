package com.ledgerguard.reconciliation.domain;

/**
 * Resolution actions for reconciliation review cases.
 */
public enum ReconciliationResolutionAction {
    /**
     * The derived ledger balance snapshot was actively repaired to match
     * the authoritative sum of posted journal transactions.
     */
    SNAPSHOT_REPAIRED,

    /**
     * Verified that the current snapshot balance already matches authoritative
     * posted journal history; no balance write was necessary.
     */
    ALREADY_CONSISTENT,

    /**
     * An operator concluded investigation of an issue that has no automated
     * financial repair (e.g. journal anomaly, provider discrepancy).
     * Modifies workflow state only; zero financial records mutated.
     */
    MANUAL_REVIEW_COMPLETED
}
