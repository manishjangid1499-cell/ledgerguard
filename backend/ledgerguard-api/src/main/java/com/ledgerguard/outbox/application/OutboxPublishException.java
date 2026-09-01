package com.ledgerguard.outbox.application;

/**
 * Exception thrown when outbox event publication fails.
 */
public class OutboxPublishException extends RuntimeException {

    public OutboxPublishException(String message) {
        super(message);
    }

    public OutboxPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
