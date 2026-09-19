package com.ledgerguard.notification.delivery;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.SocketTimeoutException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmtpEmailSender Unit Tests")
class SmtpEmailSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpEmailSender emailSender;

    @BeforeEach
    void setUp() {
        emailSender = new SmtpEmailSender(
                mailSender,
                "no-reply@ledgerguard.local",
                "LedgerGuard",
                "support@ledgerguard.local"
        );
        lenient().when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    @DisplayName("Successful send dispatches message without exception")
    void successfulSend() {
        EmailMessage message = new EmailMessage(
                "user@example.com",
                "Subject",
                "Body",
                null,
                null
        );

        emailSender.send(message);

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("SMTP 550 wraps in non-transient EmailDeliveryException")
    void smtp550ThrowsNonTransientException() {
        SMTPSendFailedException smtpEx = new SMTPSendFailedException(
                "RCPT TO", 550, "550 User unknown", null, null, null, null
        );
        MailSendException mse = new MailSendException("Send failed", smtpEx);
        doThrow(mse).when(mailSender).send(any(MimeMessage.class));

        EmailMessage message = new EmailMessage("bad@example.com", "Subject", "Body", null, null);

        assertThatThrownBy(() -> emailSender.send(message))
                .isInstanceOf(EmailDeliveryException.class)
                .satisfies(ex -> {
                    EmailDeliveryException ede = (EmailDeliveryException) ex;
                    assertThat(ede.isTransient()).isFalse();
                    assertThat(ede.getSmtpStatusCode()).isEqualTo(550);
                    assertThat(ede.getErrorCategory()).isEqualTo("PERMANENT_SMTP_5XX");
                });
    }

    @Test
    @DisplayName("SMTP 421 wraps in transient EmailDeliveryException")
    void smtp421ThrowsTransientException() {
        SMTPSendFailedException smtpEx = new SMTPSendFailedException(
                "MAIL FROM", 421, "421 Service unavailable", null, null, null, null
        );
        MailSendException mse = new MailSendException("Send failed", smtpEx);
        doThrow(mse).when(mailSender).send(any(MimeMessage.class));

        EmailMessage message = new EmailMessage("user@example.com", "Subject", "Body", null, null);

        assertThatThrownBy(() -> emailSender.send(message))
                .isInstanceOf(EmailDeliveryException.class)
                .satisfies(ex -> {
                    EmailDeliveryException ede = (EmailDeliveryException) ex;
                    assertThat(ede.isTransient()).isTrue();
                    assertThat(ede.getSmtpStatusCode()).isEqualTo(421);
                    assertThat(ede.getErrorCategory()).isEqualTo("TRANSIENT_SMTP_4XX");
                });
    }

    @Test
    @DisplayName("SMTP 535 authentication failure wraps in non-transient EmailDeliveryException")
    void smtp535ThrowsNonTransientException() {
        MailAuthenticationException authEx = new MailAuthenticationException("Authentication failed: 535 Bad credentials");
        doThrow(authEx).when(mailSender).send(any(MimeMessage.class));

        EmailMessage message = new EmailMessage("user@example.com", "Subject", "Body", null, null);

        assertThatThrownBy(() -> emailSender.send(message))
                .isInstanceOf(EmailDeliveryException.class)
                .satisfies(ex -> {
                    EmailDeliveryException ede = (EmailDeliveryException) ex;
                    assertThat(ede.isTransient()).isFalse();
                    assertThat(ede.getErrorCategory()).isEqualTo("AUTHENTICATION_FAILURE");
                });
    }

    @Test
    @DisplayName("Socket timeout wraps in transient EmailDeliveryException")
    void socketTimeoutThrowsTransientException() {
        SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
        MailSendException mse = new MailSendException("Send failed", timeout);
        doThrow(mse).when(mailSender).send(any(MimeMessage.class));

        EmailMessage message = new EmailMessage("user@example.com", "Subject", "Body", null, null);

        assertThatThrownBy(() -> emailSender.send(message))
                .isInstanceOf(EmailDeliveryException.class)
                .satisfies(ex -> {
                    EmailDeliveryException ede = (EmailDeliveryException) ex;
                    assertThat(ede.isTransient()).isTrue();
                    assertThat(ede.getErrorCategory()).isEqualTo("NETWORK_CONNECTIVITY");
                });
    }
}
