package com.ledgerguard.notification.delivery;

/**
 * Port abstraction for delivering email messages to external recipients.
 */
public interface EmailSender {

    /**
     * Sends an email message through the configured transport.
     *
     * @param message the email message to send
     * @throws EmailDeliveryException if transmission fails
     */
    void send(EmailMessage message) throws EmailDeliveryException;
}
