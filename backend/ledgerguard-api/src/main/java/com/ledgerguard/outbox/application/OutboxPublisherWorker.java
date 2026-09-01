package com.ledgerguard.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Scheduled background worker that periodically triggers outbox publication batches.
 * Multiple instances are safe because underlying row claiming uses PostgreSQL FOR UPDATE SKIP LOCKED.
 */
@Component
public class OutboxPublisherWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherWorker.class);

    private final OutboxPublisherService publisherService;
    private final boolean enabled;
    private final int batchSize;

    public OutboxPublisherWorker(
            OutboxPublisherService publisherService,
            @Value("${ledgerguard.outbox.publisher.enabled:true}") boolean enabled,
            @Value("${ledgerguard.outbox.publisher.batch-size:50}") int batchSize
    ) {
        this.publisherService = Objects.requireNonNull(publisherService, "publisherService must not be null");
        this.enabled = enabled;
        this.batchSize = batchSize <= 0 ? 50 : batchSize;
    }

    @Scheduled(fixedDelayString = "${ledgerguard.outbox.publisher.fixed-delay-ms:1000}")
    public void runPublisher() {
        if (!enabled) {
            return;
        }
        publishDirectly();
    }

    public int publishDirectly() {
        try {
            int publishedCount = publisherService.publishPendingBatch(batchSize);
            if (publishedCount > 0) {
                log.debug("Outbox publisher cycle completed: {} events published", publishedCount);
            }
            return publishedCount;
        } catch (Exception e) {
            log.error("Outbox publisher cycle encountered failure: {}", e.getMessage());
            // Intentionally catch at scheduler boundary to allow subsequent cycles to retry
            return 0;
        }
    }
}
