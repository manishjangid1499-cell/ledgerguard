package com.ledgerguard.psp.application;

import com.ledgerguard.psp.domain.ProviderWebhook;
import com.ledgerguard.psp.infrastructure.ProviderWebhookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class ProviderWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookDispatcher.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ProviderWebhookRepository webhookRepository;
    private final RestClient restClient;
    private final int batchSize;
    private final String webhookSecret;

    public ProviderWebhookDispatcher(
            ProviderWebhookRepository webhookRepository,
            @Value("${ledgerguard.psp.webhook.batch-size:50}") int batchSize,
            @Value("${ledgerguard.psp.webhook.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${ledgerguard.psp.webhook.read-timeout-seconds:5}") int readTimeoutSeconds,
            @Value("${ledgerguard.psp.webhook.secret:}") String webhookSecret
    ) {
        this.webhookRepository = webhookRepository;
        this.batchSize = batchSize;
        this.webhookSecret = webhookSecret;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @jakarta.annotation.PostConstruct
    public void validateSecret() {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("PSP webhook secret (ledgerguard.psp.webhook.secret) must be configured.");
        }
        byte[] bytes = webhookSecret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("PSP webhook secret must be at least 32 UTF-8 bytes. Current length: " + bytes.length);
        }
    }

    @Scheduled(fixedDelayString = "${ledgerguard.psp.webhook.poll-interval-ms:500}")
    @Transactional
    public void dispatchDueWebhooks() {
        Instant now = Instant.now();
        List<ProviderWebhook> due = webhookRepository.findDueWebhooksForUpdate(now, batchSize);

        if (due.isEmpty()) {
            return;
        }

        log.debug("Dispatching {} due webhooks", due.size());

        for (ProviderWebhook webhook : due) {
            try {
                byte[] rawBody = webhook.getPayload().getBytes(StandardCharsets.UTF_8);
                long timestamp = Instant.now().getEpochSecond();
                String signature = computeSignature(timestamp, rawBody);

                restClient.post()
                        .uri(webhook.getTargetUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", Long.toString(timestamp))
                        .header("X-PSP-Webhook-Signature", signature)
                        .body(rawBody)
                        .retrieve()
                        .toBodilessEntity();

                webhook.markDelivered(Instant.now());
                log.info("Webhook delivered successfully: id={}, eventId={}, target={}",
                        webhook.getId(), webhook.getEventId(), webhook.getTargetUrl());
            } catch (Exception e) {
                webhook.markFailed();
                log.warn("Webhook delivery failed: id={}, eventId={}, target={}, reason={}",
                        webhook.getId(), webhook.getEventId(), webhook.getTargetUrl(), e.getMessage());
            }
        }

        webhookRepository.saveAll(due);
    }

    private String computeSignature(long timestamp, byte[] rawBody) throws Exception {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("PSP webhook secret is not configured for signing");
        }

        byte[] timestampBytes = Long.toString(timestamp).getBytes(StandardCharsets.UTF_8);
        byte[] dotBytes = ".".getBytes(StandardCharsets.UTF_8);

        byte[] canonicalBytes = new byte[timestampBytes.length + dotBytes.length + rawBody.length];
        System.arraycopy(timestampBytes, 0, canonicalBytes, 0, timestampBytes.length);
        System.arraycopy(dotBytes, 0, canonicalBytes, timestampBytes.length, dotBytes.length);
        System.arraycopy(rawBody, 0, canonicalBytes, timestampBytes.length + dotBytes.length, rawBody.length);

        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] digest = mac.doFinal(canonicalBytes);

        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return "sha256=" + sb.toString();
    }
}
