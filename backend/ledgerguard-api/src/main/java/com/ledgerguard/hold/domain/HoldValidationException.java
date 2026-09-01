package com.ledgerguard.hold.domain;

public class HoldValidationException extends RuntimeException {
    public HoldValidationException(String message) {
        super(message);
    }
}
