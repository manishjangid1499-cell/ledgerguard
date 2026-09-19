package com.ledgerguard.notification.delivery;

/**
 * Immutable classification result for an SMTP or transport error.
 */
public record SmtpFailureClassification(
        boolean isTransient,
        Integer smtpCode,
        String category,
        String description
) {
    public static SmtpFailureClassification permanent(Integer code, String category, String description) {
        return new SmtpFailureClassification(false, code, category, description);
    }

    public static SmtpFailureClassification transientFailure(Integer code, String category, String description) {
        return new SmtpFailureClassification(true, code, category, description);
    }
}
