package com.ledgerguard.provider.application;

public class ProviderEventConflictException extends RuntimeException {
    public ProviderEventConflictException(String message) {
        super(message);
    }
}
