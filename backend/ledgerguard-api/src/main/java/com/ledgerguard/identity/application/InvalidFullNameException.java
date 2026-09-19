package com.ledgerguard.identity.application;

public class InvalidFullNameException extends RuntimeException {
    public InvalidFullNameException(String message) {
        super(message);
    }
}
