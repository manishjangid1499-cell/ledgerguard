package com.ledgerguard.reconciliation.domain;

/**
 * Classification of a reconciliation item: confirmed financial discrepancy or
 * genuinely unresolved provider observation.
 * <p>
 * Healthy results produce no item.
 */
public enum ReconciliationClassification {
    DISCREPANCY,
    UNRESOLVED
}
