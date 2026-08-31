package com.ledgerguard.identity.application;

public class ForbiddenRegistrationException extends RuntimeException {
    public ForbiddenRegistrationException(String message) {
        super(message);
    }
}
