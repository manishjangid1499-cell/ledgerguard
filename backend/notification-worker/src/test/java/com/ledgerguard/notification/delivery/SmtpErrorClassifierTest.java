package com.ledgerguard.notification.delivery;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SmtpErrorClassifier Unit Tests")
class SmtpErrorClassifierTest {

    @Test
    @DisplayName("SMTP 550 permanent failure is classified as non-transient")
    void classifiesSmtp550AsPermanent() {
        SMTPSendFailedException smtpEx = new SMTPSendFailedException(
                "RCPT TO:<bad@example.com>",
                550,
                "550 5.1.1 User unknown",
                null, null, null, null
        );
        MailSendException mailSendEx = new MailSendException("Failed to send", smtpEx);

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(mailSendEx);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.smtpCode()).isEqualTo(550);
        assertThat(classification.category()).isEqualTo("PERMANENT_SMTP_5XX");
    }

    @Test
    @DisplayName("SMTP 554 transaction failed is classified as non-transient")
    void classifiesSmtp554AsPermanent() {
        SMTPSendFailedException smtpEx = new SMTPSendFailedException(
                "DATA",
                554,
                "554 Transaction failed",
                null, null, null, null
        );

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(smtpEx);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.smtpCode()).isEqualTo(554);
        assertThat(classification.category()).isEqualTo("PERMANENT_SMTP_5XX");
    }

    @Test
    @DisplayName("SMTP 535 authentication failure is classified as non-transient")
    void classifiesSmtp535AsPermanent() {
        SMTPSendFailedException smtpEx = new SMTPSendFailedException(
                "AUTH PLAIN",
                535,
                "535 Authentication credentials invalid",
                null, null, null, null
        );

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(smtpEx);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.smtpCode()).isEqualTo(535);
        assertThat(classification.category()).isEqualTo("AUTHENTICATION_FAILURE");
    }

    @Test
    @DisplayName("MailAuthenticationException is classified as non-transient authentication failure")
    void classifiesMailAuthenticationExceptionAsPermanent() {
        MailAuthenticationException ex = new MailAuthenticationException("Authentication failed for user smtp_user");

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(ex);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.category()).isEqualTo("AUTHENTICATION_FAILURE");
    }

    @Test
    @DisplayName("Jakarta AuthenticationFailedException is classified as non-transient")
    void classifiesJakartaAuthenticationFailedExceptionAsPermanent() {
        AuthenticationFailedException ex = new AuthenticationFailedException("Invalid password");

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(ex);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.category()).isEqualTo("AUTHENTICATION_FAILURE");
    }

    @Test
    @DisplayName("SMTP 421 service unavailable is classified as transient")
    void classifiesSmtp421AsTransient() {
        SMTPSendFailedException smtpEx = new SMTPSendFailedException(
                "MAIL FROM:<no-reply@ledgerguard.local>",
                421,
                "421 4.7.0 Service unavailable, closing transmission channel",
                null, null, null, null
        );

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(smtpEx);

        assertThat(classification.isTransient()).isTrue();
        assertThat(classification.smtpCode()).isEqualTo(421);
        assertThat(classification.category()).isEqualTo("TRANSIENT_SMTP_4XX");
    }

    @Test
    @DisplayName("SMTP 450 mailbox busy is classified as transient")
    void classifiesSmtp450AsTransient() {
        SMTPSendFailedException smtpEx = new SMTPSendFailedException(
                "RCPT TO:<user@example.com>",
                450,
                "450 Mailbox busy",
                null, null, null, null
        );

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(smtpEx);

        assertThat(classification.isTransient()).isTrue();
        assertThat(classification.smtpCode()).isEqualTo(450);
        assertThat(classification.category()).isEqualTo("TRANSIENT_SMTP_4XX");
    }

    @Test
    @DisplayName("SMTPAddressFailedException with 550 is classified as non-transient")
    void classifiesSmtpAddressFailedExceptionAsPermanent() throws Exception {
        SMTPAddressFailedException addrEx = new SMTPAddressFailedException(
                new InternetAddress("bad@example.com"),
                "RCPT TO",
                550,
                "550 Recipient address rejected"
        );

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(addrEx);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.smtpCode()).isEqualTo(550);
        assertThat(classification.category()).isEqualTo("PERMANENT_SMTP_5XX");
    }

    @Test
    @DisplayName("AddressException syntax error is classified as non-transient")
    void classifiesAddressExceptionAsPermanent() {
        AddressException ex = new AddressException("Missing domain name", "user@");

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(ex);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.category()).isEqualTo("INVALID_RECIPIENT");
    }

    @Test
    @DisplayName("SendFailedException with invalid addresses is classified as non-transient")
    void classifiesSendFailedExceptionWithInvalidAddressesAsPermanent() throws Exception {
        SendFailedException ex = new SendFailedException(
                "Invalid Addresses",
                null,
                new InternetAddress[]{},
                new InternetAddress[]{},
                new InternetAddress[]{new InternetAddress("invalid@domain.invalid")}
        );

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(ex);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.category()).isEqualTo("INVALID_RECIPIENT");
    }

    @Test
    @DisplayName("SocketTimeoutException is classified as transient network connectivity")
    void classifiesSocketTimeoutAsTransient() {
        SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
        MailSendException mailSendEx = new MailSendException("Mail send failed", timeout);

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(mailSendEx);

        assertThat(classification.isTransient()).isTrue();
        assertThat(classification.category()).isEqualTo("NETWORK_CONNECTIVITY");
    }

    @Test
    @DisplayName("ConnectException is classified as transient network connectivity")
    void classifiesConnectExceptionAsTransient() {
        ConnectException connEx = new ConnectException("Connection refused: connect");

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(connEx);

        assertThat(classification.isTransient()).isTrue();
        assertThat(classification.category()).isEqualTo("NETWORK_CONNECTIVITY");
    }

    @Test
    @DisplayName("Inspects linked next-exception in MessagingException chain")
    void inspectsMessagingExceptionNextException() {
        MessagingException root = new MessagingException("Top level transport failure");
        SMTPSendFailedException nextEx = new SMTPSendFailedException(
                "DATA",
                550,
                "550 You can only send testing emails to your own email address",
                null, null, null, null
        );
        root.setNextException(nextEx);

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(root);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.smtpCode()).isEqualTo(550);
        assertThat(classification.category()).isEqualTo("PERMANENT_SMTP_5XX");
    }

    @Test
    @DisplayName("Inspects MailSendException message exceptions array and failed messages map")
    void inspectsMailSendExceptionMessageExceptions() {
        SMTPSendFailedException cause = new SMTPSendFailedException(
                "RCPT TO",
                554,
                "554 Delivery not authorized",
                null, null, null, null
        );
        MailSendException mse = new MailSendException(
                Map.of("message-id-1", cause)
        );

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(mse);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.smtpCode()).isEqualTo(554);
    }

    @Test
    @DisplayName("Text matching extracts enhanced status code 5.1.1 as permanent")
    void extractsEnhancedStatusCode511AsPermanent() {
        MessagingException ex = new MessagingException("Server said: 5.1.1 Bad destination mailbox address");

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(ex);

        assertThat(classification.isTransient()).isFalse();
        assertThat(classification.category()).isEqualTo("PERMANENT_SMTP_5XX");
    }

    @Test
    @DisplayName("Port 587 in error message does not trigger false positive 5xx classification")
    void port587DoesNotTriggerFalse5xx() {
        MessagingException ex = new MessagingException("Could not connect to SMTP host: smtp-relay.example.com, port: 587");

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(ex);

        assertThat(classification.isTransient()).isTrue();
        assertThat(classification.category()).isEqualTo("NETWORK_CONNECTIVITY");
    }

    @Test
    @DisplayName("Unknown transport error falls back conservatively to transient")
    void unknownTransportErrorFallsBackToTransient() {
        RuntimeException ex = new RuntimeException("Some unforeseen low-level protocol glitch");

        SmtpFailureClassification classification = SmtpErrorClassifier.classify(ex);

        assertThat(classification.isTransient()).isTrue();
        assertThat(classification.category()).isEqualTo("UNCLASSIFIED_TRANSPORT");
    }

    @Test
    @DisplayName("Sanitizes sensitive tokens and passwords from error messages")
    void sanitizesSensitiveTokens() {
        String raw = "Failed AUTH with password=SuperSecretPassword123 and bearer eyJhbGciOiJIUzI1Ni.abc.xyz";
        String sanitized = SmtpErrorClassifier.sanitizeErrorMessage(raw);

        assertThat(sanitized).doesNotContain("SuperSecretPassword123");
        assertThat(sanitized).contains("password=***");
        assertThat(sanitized).contains("bearer ***");
    }
}
