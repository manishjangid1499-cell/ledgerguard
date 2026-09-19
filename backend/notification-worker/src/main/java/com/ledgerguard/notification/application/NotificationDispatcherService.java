package com.ledgerguard.notification.application;

import com.ledgerguard.notification.delivery.EmailDeliveryException;
import com.ledgerguard.notification.delivery.EmailMessage;
import com.ledgerguard.notification.delivery.EmailSender;
import com.ledgerguard.notification.delivery.EmailTemplateRenderer;
import com.ledgerguard.notification.domain.NotificationDelivery;
import com.ledgerguard.notification.infrastructure.NotificationDeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class NotificationDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcherService.class);

    private final String workerId = "worker-" + UUID.randomUUID();
    private final NotificationDeliveryRepository deliveryRepository;
    private final EmailSender emailSender;
    private final EmailTemplateRenderer templateRenderer;
    private final MeterRegistry meterRegistry;

    private final boolean emailEnabled;
    private final int batchSize;
    private final long leaseDurationSeconds;
    private final int maxAttempts;
    private final long initialRetryDelaySeconds;
    private final long maxRetryDelaySeconds;
    private final String defaultFrom;
    private final String defaultFromName;

    public NotificationDispatcherService(
            NotificationDeliveryRepository deliveryRepository,
            EmailSender emailSender,
            EmailTemplateRenderer templateRenderer,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            @Value("${ledgerguard.notification.email.enabled:true}") boolean emailEnabled,
            @Value("${ledgerguard.notification.dispatcher.batch-size:20}") int batchSize,
            @Value("${ledgerguard.notification.dispatcher.lease-duration-seconds:120}") long leaseDurationSeconds,
            @Value("${ledgerguard.notification.dispatcher.max-attempts:5}") int maxAttempts,
            @Value("${ledgerguard.notification.dispatcher.initial-retry-delay-seconds:30}") long initialRetryDelaySeconds,
            @Value("${ledgerguard.notification.dispatcher.max-retry-delay-seconds:600}") long maxRetryDelaySeconds,
            @Value("${ledgerguard.notification.email.from:no-reply@ledgerguard.local}") String defaultFrom,
            @Value("${ledgerguard.notification.email.from-name:LedgerGuard}") String defaultFromName
    ) {
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository, "deliveryRepository must not be null");
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender must not be null");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer must not be null");
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.emailEnabled = emailEnabled;
        this.batchSize = batchSize > 0 ? batchSize : 20;
        this.leaseDurationSeconds = leaseDurationSeconds > 0 ? leaseDurationSeconds : 120;
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
        this.initialRetryDelaySeconds = initialRetryDelaySeconds > 0 ? initialRetryDelaySeconds : 30;
        this.maxRetryDelaySeconds = maxRetryDelaySeconds > 0 ? maxRetryDelaySeconds : 600;
        this.defaultFrom = defaultFrom;
        this.defaultFromName = defaultFromName;
    }

    @Scheduled(fixedDelayString = "${ledgerguard.notification.dispatcher.poll-interval-ms:1000}")
    public void pollAndDispatch() {
        if (!emailEnabled) {
            return;
        }
        dispatchBatch();
    }

    /**
     * Executes one claim-send cycle. Returns the number of deliveries dispatched.
     */
    public int dispatchBatch() {
        List<NotificationDelivery> claimed = claimBatch(batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        int dispatchedCount = 0;
        for (NotificationDelivery delivery : claimed) {
            boolean success = processSingleDelivery(delivery);
            if (success) {
                dispatchedCount++;
            }
        }
        return dispatchedCount;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<NotificationDelivery> claimBatch(int limit) {
        Instant now = Instant.now();
        List<NotificationDelivery> due = deliveryRepository.claimDueDeliveries(now, limit);
        if (due.isEmpty()) {
            return Collections.emptyList();
        }

        Instant lockExpiry = now.plusSeconds(leaseDurationSeconds);
        for (NotificationDelivery delivery : due) {
            delivery.claim(workerId, lockExpiry);
        }
        deliveryRepository.saveAll(due);
        deliveryRepository.flush();
        return due;
    }

    private boolean processSingleDelivery(NotificationDelivery delivery) {
        try {
            String body = templateRenderer.renderBody(delivery.getTemplateKey(), delivery.getPayloadJson());
            EmailMessage message = new EmailMessage(
                    delivery.getRecipientEmail(),
                    delivery.getSubject(),
                    body,
                    defaultFrom,
                    defaultFromName
            );

            emailSender.send(message);
            recordSuccess(delivery.getId());
            return true;
        } catch (EmailDeliveryException e) {
            recordFailure(delivery.getId(), e.getMessage(), e.isTransient());
            return false;
        } catch (Exception e) {
            recordFailure(delivery.getId(), sanitizeError(e.getMessage()), false);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID deliveryId) {
        deliveryRepository.findById(deliveryId).ifPresent(d -> {
            d.markSent(Instant.now());
            deliveryRepository.saveAndFlush(d);
            log.info("Delivery {} marked SENT for recipient {}", d.getId(), d.getRecipientEmail());
            if (meterRegistry != null) {
                meterRegistry.counter("notification.email.sent").increment();
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID deliveryId, String errorMessage, boolean transientFailure) {
        deliveryRepository.findById(deliveryId).ifPresent(d -> {
            String sanitized = sanitizeError(errorMessage);
            int nextAttemptCount = d.getAttemptCount() + 1;

            if (transientFailure && nextAttemptCount < maxAttempts) {
                long delaySeconds = calculateBackoffSeconds(d.getAttemptCount());
                Instant nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
                d.markRetry(sanitized, nextAttemptAt);
                deliveryRepository.saveAndFlush(d);
                log.warn("Delivery {} transient failure (attempt {}/{}). Retrying at {}: {}",
                        d.getId(), nextAttemptCount, maxAttempts, nextAttemptAt, sanitized);
                if (meterRegistry != null) {
                    meterRegistry.counter("notification.email.retry").increment();
                }
            } else {
                d.markFailed(sanitized);
                deliveryRepository.saveAndFlush(d);
                log.error("Delivery {} permanently failed after {} attempts: {}",
                        d.getId(), nextAttemptCount, sanitized);
                if (meterRegistry != null) {
                    meterRegistry.counter("notification.email.failed").increment();
                }
            }
        });
    }

    private long calculateBackoffSeconds(int currentAttempts) {
        long multiplier = 1L << Math.min(currentAttempts, 10);
        long delay = initialRetryDelaySeconds * multiplier;
        return Math.min(delay, maxRetryDelaySeconds);
    }

    private String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "Unknown error";
        }
        String sanitized = error.replaceAll("(?i)password=[^&;\\s]+", "password=***")
                .replaceAll("(?i)bearer\\s+[a-zA-Z0-9._-]+", "bearer ***");
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500);
        }
        return sanitized;
    }

    public String getWorkerId() {
        return workerId;
    }
}
