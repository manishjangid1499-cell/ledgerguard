package com.ledgerguard.psp.application;

public class ConflictingReplayException extends RuntimeException {
    public ConflictingReplayException(String message) {
        super(message);
    }
}
