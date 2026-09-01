package com.ledgerguard.funding.infrastructure;

/**
 * Thrown when the external PSP returns an unexpected HTTP status, server error (5xx),
 * or semantically invalid/mismatched payload.
 */
public class PspProtocolException extends RuntimeException {

    private final Integer statusCode;

    public PspProtocolException(String message) {
        super(message);
        this.statusCode = null;
    }

    public PspProtocolException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
