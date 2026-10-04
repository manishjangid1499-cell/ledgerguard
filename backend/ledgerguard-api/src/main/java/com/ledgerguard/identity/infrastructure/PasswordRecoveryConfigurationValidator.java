package com.ledgerguard.identity.infrastructure;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Validates password recovery configuration at application startup.
 * Enforces production HTTPS invariants, bounded timeouts, and sanitizes configuration logs.
 */
@Component
public class PasswordRecoveryConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryConfigurationValidator.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final PasswordRecoveryProperties properties;
    private final Environment environment;

    @Value("${spring.mail.host:localhost}")
    private String mailHost;

    @Value("${spring.mail.port:1025}")
    private int mailPort;

    @Value("${spring.mail.properties.mail.smtp.auth:false}")
    private boolean smtpAuth;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}")
    private boolean starttlsEnable;

    @Value("${spring.mail.properties.mail.smtp.connectiontimeout:10000}")
    private int connectionTimeoutMs;

    @Value("${spring.mail.properties.mail.smtp.timeout:10000}")
    private int readTimeoutMs;

    @Value("${spring.mail.properties.mail.smtp.writetimeout:10000}")
    private int writeTimeoutMs;

    public PasswordRecoveryConfigurationValidator(PasswordRecoveryProperties properties,
                                                  Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        validateFrontendBaseUrl();
        validateSenderEmail();
        validateTimeouts();

        log.info("Password recovery initialized: frontendBaseUrl={}, fromEmail={}, tokenTtl={}, cooldown={}, smtpHost={}, smtpPort={}, smtpAuth={}, starttls={}",
                properties.getFrontendBaseUrl(),
                properties.getFromEmail(),
                properties.getTokenTtl(),
                properties.getPerEmailCooldown(),
                mailHost,
                mailPort,
                smtpAuth,
                starttlsEnable);
    }

    private void validateFrontendBaseUrl() {
        String url = properties.getFrontendBaseUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Password recovery configuration failed: frontendBaseUrl must not be blank.");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Password recovery configuration failed: frontendBaseUrl is not a valid URI: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalStateException("Password recovery configuration failed: frontendBaseUrl must use http or https scheme.");
        }

        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"))
                && !environment.acceptsProfiles(Profiles.of("test"));
        if (isProd) {
            if (!scheme.equalsIgnoreCase("https")) {
                throw new IllegalStateException("Password recovery configuration failed: production environment requires HTTPS frontendBaseUrl: " + url);
            }
        } else {
            if (scheme.equalsIgnoreCase("http")) {
                String host = uri.getHost();
                if (host == null || (!host.equalsIgnoreCase("localhost") && !host.equals("127.0.0.1"))) {
                    throw new IllegalStateException("Password recovery configuration failed: plain HTTP is only permitted for localhost development: " + url);
                }
            }
        }
    }

    private void validateSenderEmail() {
        String from = properties.getFromEmail();
        if (from == null || from.isBlank() || !EMAIL_PATTERN.matcher(from.trim()).matches()) {
            throw new IllegalStateException("Password recovery configuration failed: fromEmail must be a valid email address.");
        }
    }

    private void validateTimeouts() {
        if (connectionTimeoutMs <= 0 || connectionTimeoutMs > 60000) {
            throw new IllegalStateException("Password recovery configuration failed: mail connection timeout must be between 1ms and 60000ms.");
        }
        if (readTimeoutMs <= 0 || readTimeoutMs > 60000) {
            throw new IllegalStateException("Password recovery configuration failed: mail read timeout must be between 1ms and 60000ms.");
        }
        if (writeTimeoutMs <= 0 || writeTimeoutMs > 60000) {
            throw new IllegalStateException("Password recovery configuration failed: mail write timeout must be between 1ms and 60000ms.");
        }
    }
}
