package com.ledgerguard.notification.delivery;

/**
 * Thrown when outbound email transmission fails.
 * Captures whether the failure is transient (retryable) or permanent (terminal),
 * along with optional SMTP return code and classification category.
 */
public class EmailDeliveryException extends RuntimeException {

    private final boolean transientFailure;
    private final Integer smtpStatusCode;
    private final String errorCategory;

    public EmailDeliveryException(String message) {
        this(message, null, true, null, "UNSPECIFIED");
    }

    public EmailDeliveryException(String message, Throwable cause) {
        this(message, cause, true, null, "UNSPECIFIED");
    }

    public EmailDeliveryException(String message, boolean transientFailure) {
        this(message, null, transientFailure, null, "UNSPECIFIED");
    }

    public EmailDeliveryException(String message, Throwable cause, boolean transientFailure) {
        this(message, cause, transientFailure, null, "UNSPECIFIED");
    }

    public EmailDeliveryException(String message, boolean transientFailure, Integer smtpStatusCode, String errorCategory) {
        this(message, null, transientFailure, smtpStatusCode, errorCategory);
    }

    public EmailDeliveryException(String message, Throwable cause, boolean transientFailure, Integer smtpStatusCode, String errorCategory) {
        super(message, cause);
        this.transientFailure = transientFailure;
        this.smtpStatusCode = smtpStatusCode;
        this.errorCategory = errorCategory != null ? errorCategory : "UNSPECIFIED";
    }

    public boolean isTransient() {
        return transientFailure;
    }

    public Integer getSmtpStatusCode() {
        return smtpStatusCode;
    }

    public String getErrorCategory() {
        return errorCategory;
    }
}
