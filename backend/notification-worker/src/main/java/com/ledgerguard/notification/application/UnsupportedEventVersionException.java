package com.ledgerguard.notification.application;

public class UnsupportedEventVersionException extends RuntimeException {
    public UnsupportedEventVersionException(String message) {
        super(message);
    }
}
