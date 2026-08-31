package com.ledgerguard.idempotency.domain;

/**
 * Thrown when an existing idempotency slot is observed in the IN_PROGRESS state.
 */
public class IdempotencyOperationInProgressException extends RuntimeException {

    public IdempotencyOperationInProgressException(String message) {
        super(message);
    }
}
