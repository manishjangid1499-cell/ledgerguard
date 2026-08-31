package com.ledgerguard.shared.error;

/**
 * Standardized generic API error codes for LedgerGuard.
 */
public final class ApiErrorCode {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ApiErrorCode() {
        // Utility class
    }
}
