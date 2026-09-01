package com.ledgerguard.refund.domain;

/**
 * Thrown when a requested refund exceeds the remaining refundable amount of the parent payment.
 */
public class RefundLimitExceededException extends RuntimeException {
    public RefundLimitExceededException(String message) {
        super(message);
    }
}
