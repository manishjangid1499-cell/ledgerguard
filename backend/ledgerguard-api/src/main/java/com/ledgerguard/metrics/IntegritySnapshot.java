package com.ledgerguard.metrics;

/**
 * Immutable snapshot of database-backed financial integrity and operational metrics.
 */
public record IntegritySnapshot(
        long unbalancedJournalCount,
        long reconciliationDiscrepancies,
        double outboxLagSeconds
) {}