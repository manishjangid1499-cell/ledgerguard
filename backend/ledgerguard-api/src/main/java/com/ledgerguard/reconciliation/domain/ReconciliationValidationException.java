package com.ledgerguard.reconciliation.domain;

/**
 * Thrown when an input or parameter validation fails during a reconciliation operation.
 */
public class ReconciliationValidationException extends RuntimeException {

    public ReconciliationValidationException(String message) {
        super(message);
    }
}
