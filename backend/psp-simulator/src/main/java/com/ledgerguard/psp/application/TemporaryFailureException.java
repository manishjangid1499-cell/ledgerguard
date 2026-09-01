package com.ledgerguard.psp.application;

public class TemporaryFailureException extends RuntimeException {
    public TemporaryFailureException(String message) {
        super(message);
    }
}
