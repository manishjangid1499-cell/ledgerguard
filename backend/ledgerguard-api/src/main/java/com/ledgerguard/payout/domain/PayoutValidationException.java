package com.ledgerguard.payout.domain;

public class PayoutValidationException extends RuntimeException {

    public PayoutValidationException(String message) {
        super(message);
    }

    public PayoutValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
