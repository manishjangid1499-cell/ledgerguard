package com.ledgerguard.idempotency.domain;

/**
 * Thrown when an idempotency key is reused with a different request fingerprint.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
