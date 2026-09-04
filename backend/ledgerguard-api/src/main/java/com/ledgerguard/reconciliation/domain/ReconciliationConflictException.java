package com.ledgerguard.reconciliation.domain;

/**
 * Thrown when a reconciliation case state transition, claim, or repair conflicts
 * with existing invariants or concurrent operations.
 */
public class ReconciliationConflictException extends RuntimeException {

    public ReconciliationConflictException(String message) {
        super(message);
    }
}
