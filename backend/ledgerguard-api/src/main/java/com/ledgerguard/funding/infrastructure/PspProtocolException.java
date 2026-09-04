package com.ledgerguard.funding.infrastructure;

/**
 * Thrown when the external PSP returns an unexpected HTTP status, server error (5xx),
 * or semantically invalid/mismatched payload.
 */
public class PspProtocolException extends RuntimeException {

    private final Integer statusCode;
    private final String providerErrorType;

    public PspProtocolException(String message) {
        this(message, null, (String) null);
    }

    public PspProtocolException(String message, int statusCode) {
        this(message, statusCode, (String) null);
    }

    public PspProtocolException(String message, Integer statusCode, String providerErrorType) {
        this(message, null, statusCode, providerErrorType);
    }

    public PspProtocolException(String message, Throwable cause, Integer statusCode) {
        this(message, cause, statusCode, null);
    }

    public PspProtocolException(String message, Throwable cause, Integer statusCode, String providerErrorType) {
        super(message, cause);
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
