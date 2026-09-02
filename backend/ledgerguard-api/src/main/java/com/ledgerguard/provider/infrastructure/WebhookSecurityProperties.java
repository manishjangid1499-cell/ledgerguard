package com.ledgerguard.provider.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Configuration
@ConfigurationProperties(prefix = "ledgerguard.psp.webhook")
public class WebhookSecurityProperties {

    private static final int MIN_SECRET_BYTES = 32;

    private String secret;
    private long maxClockSkewSeconds = 300;
    private String webhookUrl = "http://localhost:8080/api/provider/webhooks";

    @PostConstruct
    public void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("PSP webhook secret (ledgerguard.psp.webhook.secret) must be configured.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("PSP webhook secret must be at least 32 UTF-8 bytes. Current length: " + bytes.length);
        }
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getMaxClockSkewSeconds() {
        return maxClockSkewSeconds;
    }

    public void setMaxClockSkewSeconds(long maxClockSkewSeconds) {
        this.maxClockSkewSeconds = maxClockSkewSeconds;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }
}
