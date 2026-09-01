package com.ledgerguard.refund.domain;

/**
 * Thrown when an attempt is made to refund a payment that is not in SUCCEEDED status.
 */
public class PaymentNotRefundableException extends RuntimeException {
    public PaymentNotRefundableException(String message) {
        super(message);
    }
}
