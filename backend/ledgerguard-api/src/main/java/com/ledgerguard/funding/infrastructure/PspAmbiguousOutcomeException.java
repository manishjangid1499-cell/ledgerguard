package com.ledgerguard.funding.infrastructure;

/**
 * Thrown when at least one dispatched provider CREATE attempt had an ambiguous outcome
 * (e.g. transport timeout, connection reset, ambiguous 5xx, malformed 2xx) and no subsequent
 * authoritative provider response was obtained during retries.
 */
public class PspAmbiguousOutcomeException extends RuntimeException {

    private final int attemptsMade;

    public PspAmbiguousOutcomeException(String message, Throwable cause, int attemptsMade) {
        super(message, cause);
        this.attemptsMade = attemptsMade;
    }

    public int getAttemptsMade() {
        return attemptsMade;
    }
}
