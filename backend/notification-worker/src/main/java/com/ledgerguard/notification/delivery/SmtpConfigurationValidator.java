package com.ledgerguard.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates SMTP configuration at application startup.
 * Ensures required settings are present and correctly formatted,
 * while preventing sensitive credentials (passwords) from ever being logged.
 */
@Component
public class SmtpConfigurationValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SmtpConfigurationValidator.class);

    private final boolean emailEnabled;
    private final String host;
    private final int port;
    private final boolean auth;
    private final boolean starttlsEnable;
    private final boolean starttlsRequired;
    private final String username;
    private final String password;
    private final String defaultFrom;
    private final String defaultFromName;
    private final String defaultReplyTo;

    public SmtpConfigurationValidator(
            @Value("${ledgerguard.notification.email.enabled:true}") boolean emailEnabled,
            @Value("${spring.mail.host:localhost}") String host,
            @Value("${spring.mail.port:1025}") int port,
            @Value("${spring.mail.properties.mail.smtp.auth:false}") boolean auth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") boolean starttlsEnable,
            @Value("${spring.mail.properties.mail.smtp.starttls.required:false}") boolean starttlsRequired,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${ledgerguard.notification.email.from:no-reply@ledgerguard.local}") String defaultFrom,
            @Value("${ledgerguard.notification.email.from-name:LedgerGuard}") String defaultFromName,
            @Value("${ledgerguard.notification.email.reply-to:}") String defaultReplyTo
    ) {
        this.emailEnabled = emailEnabled;
        this.host = host;
        this.port = port;
        this.auth = auth;
        this.starttlsEnable = starttlsEnable;
        this.starttlsRequired = starttlsRequired;
        this.username = username;
        this.password = password;
        this.defaultFrom = defaultFrom;
        this.defaultFromName = defaultFromName;
        this.defaultReplyTo = defaultReplyTo;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    public void validate() {
        if (!emailEnabled) {
            log.info("Email notification delivery is disabled (ledgerguard.notification.email.enabled=false).");
            return;
        }

        if (host == null || host.trim().isEmpty()) {
            throw new IllegalStateException("SMTP configuration error: Host must not be blank when email notifications are enabled");
        }

        if (port < 1 || port > 65535) {
            throw new IllegalStateException("SMTP configuration error: Port must be between 1 and 65535, found: " + port);
        }

        if (defaultFrom == null || defaultFrom.trim().isEmpty()) {
            throw new IllegalStateException("SMTP configuration error: From address must not be blank when email notifications are enabled");
        }

        if (auth) {
            if (username == null || username.trim().isEmpty()) {
                throw new IllegalStateException("SMTP configuration error: Username must not be blank when SMTP authentication is enabled");
            }
            if (password == null || password.trim().isEmpty()) {
                throw new IllegalStateException("SMTP configuration error: Password must not be blank when SMTP authentication is enabled");
            }
        }

        log.info("SMTP configuration validated successfully: host={}, port={}, auth={}, starttls.enable={}, starttls.required={}, from='{} <{}>', replyTo='{}'",
                host,
                port,
                auth,
                starttlsEnable,
                starttlsRequired,
                defaultFromName != null ? defaultFromName : "",
                defaultFrom,
                defaultReplyTo != null ? defaultReplyTo : "");
    }
}
