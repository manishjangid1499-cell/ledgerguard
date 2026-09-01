package com.ledgerguard.payment.domain;

/**
 * Explicit lifecycle status for an internal merchant payment.
 */
public enum PaymentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED
}
