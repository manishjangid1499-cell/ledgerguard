package com.ledgerguard.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "ledgerguard.security.password-recovery")
public class PasswordRecoveryProperties {

    /**
     * Lifetime of issued password reset tokens.
     * Must be between 1 minute and 24 hours. Default: 15 minutes.
     */
    private Duration tokenTtl = Duration.ofMinutes(15);

    /**
     * Trusted frontend base URL used to construct password reset links.
     * Must use HTTPS in production.
     */
    private String frontendBaseUrl = "http://localhost:5173";

    /**
     * Minimum interval between password reset requests for the same email address.
     * Prevents email flooding / mail bombing. Default: 60 seconds.
     */
    private Duration perEmailCooldown = Duration.ofSeconds(60);

    /**
     * Sender email address for password recovery emails.
     */
    private String fromEmail = "no-reply@ledgerguard.local";

    /**
     * Sender display name.
     */
    private String fromName = "LedgerGuard Security";

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("tokenTtl must be positive");
        }
        if (tokenTtl.compareTo(Duration.ofMinutes(1)) < 0 || tokenTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("tokenTtl must be between 1 minute and 24 hours");
        }
        this.tokenTtl = tokenTtl;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
            throw new IllegalArgumentException("frontendBaseUrl must not be blank");
        }
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
    }

    public Duration getPerEmailCooldown() {
        return perEmailCooldown;
    }

    public void setPerEmailCooldown(Duration perEmailCooldown) {
        if (perEmailCooldown == null || perEmailCooldown.isNegative()) {
            throw new IllegalArgumentException("perEmailCooldown must not be negative");
        }
        this.perEmailCooldown = perEmailCooldown;
    }

    public String getFromEmail() {
        return fromEmail;
    }

    public void setFromEmail(String fromEmail) {
        this.fromEmail = fromEmail;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }
}
