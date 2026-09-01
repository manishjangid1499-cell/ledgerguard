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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class ProviderWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookDispatcher.class);

    private final ProviderWebhookRepository webhookRepository;
    private final RestClient restClient;
    private final int batchSize;

    public ProviderWebhookDispatcher(
            ProviderWebhookRepository webhookRepository,
            @Value("${ledgerguard.psp.webhook.batch-size:50}") int batchSize,
            @Value("${ledgerguard.psp.webhook.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${ledgerguard.psp.webhook.read-timeout-seconds:5}") int readTimeoutSeconds
    ) {
        this.webhookRepository = webhookRepository;
        this.batchSize = batchSize;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
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
                restClient.post()
                        .uri(webhook.getTargetUrl())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(webhook.getPayload())
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
}
