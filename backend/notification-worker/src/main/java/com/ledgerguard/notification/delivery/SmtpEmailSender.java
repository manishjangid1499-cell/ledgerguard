package com.ledgerguard.notification.delivery;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String defaultFrom;
    private final String defaultFromName;
    private final String defaultReplyTo;

    @org.springframework.beans.factory.annotation.Autowired
    public SmtpEmailSender(
            JavaMailSender mailSender,
            @Value("${ledgerguard.notification.email.from:no-reply@ledgerguard.local}") String defaultFrom,
            @Value("${ledgerguard.notification.email.from-name:LedgerGuard}") String defaultFromName,
            @Value("${ledgerguard.notification.email.reply-to:}") String defaultReplyTo
    ) {
        this.mailSender = Objects.requireNonNull(mailSender, "mailSender must not be null");
        this.defaultFrom = defaultFrom;
        this.defaultFromName = defaultFromName;
        this.defaultReplyTo = defaultReplyTo;
    }

    public SmtpEmailSender(
            JavaMailSender mailSender,
            String defaultFrom,
            String defaultFromName
    ) {
        this(mailSender, defaultFrom, defaultFromName, null);
    }

    @Override
    public void send(EmailMessage message) throws EmailDeliveryException {
        Objects.requireNonNull(message, "EmailMessage must not be null");

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());

            String from = (message.from() != null && !message.from().isBlank()) ? message.from() : defaultFrom;
            String fromName = (message.fromName() != null && !message.fromName().isBlank()) ? message.fromName() : defaultFromName;

            if (fromName != null && !fromName.isBlank()) {
                helper.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            } else {
                helper.setFrom(from);
            }

            String replyTo = (message.replyTo() != null && !message.replyTo().isBlank()) ? message.replyTo() : defaultReplyTo;
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }

            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.body(), false);

            mailSender.send(mimeMessage);
            log.info("Successfully dispatched email: to={}, subject='{}'", message.to(), message.subject());
        } catch (Exception e) {
            SmtpFailureClassification classification = SmtpErrorClassifier.classify(e);
            String sanitizedMsg = SmtpErrorClassifier.sanitizeErrorMessage(e.getMessage());
            if (classification.isTransient()) {
                log.warn("Transient email delivery failure to {} [category={}, code={}]: {}",
                        message.to(), classification.category(), classification.smtpCode(), sanitizedMsg);
            } else {
                log.error("Permanent email delivery failure to {} [category={}, code={}]: {}",
                        message.to(), classification.category(), classification.smtpCode(), sanitizedMsg);
            }
            throw new EmailDeliveryException(
                    sanitizedMsg,
                    e,
                    classification.isTransient(),
                    classification.smtpCode(),
                    classification.category()
            );
        }
    }
}
