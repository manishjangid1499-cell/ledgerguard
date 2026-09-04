package com.ledgerguard.funding.infrastructure;

import java.util.Objects;

/**
 * Thrown when an outbound provider call is rejected locally before any network dispatch occurs
 * due to an OPEN circuit breaker or a saturated Bulkhead.
 */
public class PspCallRejectedException extends RuntimeException {

    public enum Reason {
        CIRCUIT_OPEN,
        BULKHEAD_FULL
    }

    private final Reason reason;

    public PspCallRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason getReason() {
        return reason;
    }
}
