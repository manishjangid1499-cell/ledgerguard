package com.ledgerguard.reconciliation.domain;

/**
 * Which reconciliation level produced a reconciliation item.
 */
public enum ReconciliationLevel {
    JOURNAL_BALANCE,
    SNAPSHOT_CONSISTENCY,
    PROVIDER_SETTLEMENT
}
