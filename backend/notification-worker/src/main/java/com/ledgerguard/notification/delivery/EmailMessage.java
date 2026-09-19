package com.ledgerguard.notification.delivery;

import java.util.Objects;

/**
 * Encapsulates an outbound email notification payload.
 */
public record EmailMessage(
        String to,
        String subject,
        String body,
        String from,
        String fromName,
        String replyTo
) {
    public EmailMessage {
        Objects.requireNonNull(to, "recipient 'to' must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }

    public EmailMessage(String to, String subject, String body, String from, String fromName) {
        this(to, subject, body, from, fromName, null);
    }
}
