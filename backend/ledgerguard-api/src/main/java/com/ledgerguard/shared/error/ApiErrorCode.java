package com.ledgerguard.shared.error;

/**
 * Standardized generic API error codes for LedgerGuard.
 */
public final class ApiErrorCode {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // Phase 4 Security Error Codes
    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";

    // Phase 9 & 10 & 11 Error Codes
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String IDEMPOTENCY_OPERATION_IN_PROGRESS = "IDEMPOTENCY_OPERATION_IN_PROGRESS";
    public static final String INVALID_TRANSFER = "INVALID_TRANSFER";
    public static final String INVALID_PAYMENT = "INVALID_PAYMENT";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private ApiErrorCode() {
        // Utility class
    }
}
