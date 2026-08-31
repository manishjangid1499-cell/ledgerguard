package com.ledgerguard.idempotency.domain;

/**
 * Lifecycle status of an idempotency record.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
