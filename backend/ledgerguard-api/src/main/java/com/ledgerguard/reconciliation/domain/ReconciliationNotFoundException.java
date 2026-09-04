package com.ledgerguard.reconciliation.domain;

/**
 * Thrown when a requested reconciliation run, item, or case is not found.
 */
public class ReconciliationNotFoundException extends RuntimeException {

    public ReconciliationNotFoundException(String message) {
        super(message);
    }
}
