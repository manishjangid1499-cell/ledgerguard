package com.ledgerguard.payment.domain;

/**
 * Thrown when a merchant payment command fails business validation.
 */
public class PaymentValidationException extends RuntimeException {

    public PaymentValidationException(String message) {
        super(message);
    }
}
