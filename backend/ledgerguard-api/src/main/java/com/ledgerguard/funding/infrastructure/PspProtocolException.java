package com.ledgerguard.funding.infrastructure;

/**
 * Thrown when the external PSP returns an unexpected HTTP status, server error (5xx),
 * or semantically invalid/mismatched payload.
 */
public class PspProtocolException extends RuntimeException {

    private final Integer statusCode;
    private final String providerErrorType;

    public PspProtocolException(String message) {
        this(message, null, null);
    }

    public PspProtocolException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public PspProtocolException(String message, Integer statusCode, String providerErrorType) {
        super(message);
        this.statusCode = statusCode;
        this.providerErrorType = providerErrorType;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getProviderErrorType() {
        return providerErrorType;
    }
}
