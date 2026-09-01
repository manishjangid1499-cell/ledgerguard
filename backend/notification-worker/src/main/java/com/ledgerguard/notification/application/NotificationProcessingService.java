package com.ledgerguard.notification.application;

import com.ledgerguard.notification.domain.IncomingDomainEvent;
import com.ledgerguard.notification.domain.ProcessingOutcome;
import com.ledgerguard.notification.infrastructure.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class NotificationProcessingService {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessingService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationDeliveryService notificationDeliveryService;

    public NotificationProcessingService(
            ProcessedEventRepository processedEventRepository,
            NotificationDeliveryService notificationDeliveryService
    ) {
        this.processedEventRepository = Objects.requireNonNull(processedEventRepository, "processedEventRepository must not be null");
        this.notificationDeliveryService = Objects.requireNonNull(notificationDeliveryService, "notificationDeliveryService must not be null");
    }

    /**
     * Atomically claims the event in the processed_events table and creates a durable notification delivery record.
     * Both actions execute inside the same database transaction.
     *
     * @param event the incoming domain event
     * @return PROCESSED if this was the first delivery, or DUPLICATE_SKIPPED if already processed
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ProcessingOutcome processEvent(IncomingDomainEvent event) {
        Instant now = Instant.now();
        int inserted = processedEventRepository.tryInsertClaim(
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.aggregateType(),
                event.aggregateId(),
                now
        );

        if (inserted == 0) {
            log.info("Duplicate event detected, skipping side effect: eventId={}, eventType={}, aggregateId={}",
                    event.eventId(), event.eventType(), event.aggregateId());
            return ProcessingOutcome.DUPLICATE_SKIPPED;
        }

        notificationDeliveryService.recordDelivery(event);
        log.info("Notification delivery created: eventId={}, eventType={}, aggregateId={}",
                event.eventId(), event.eventType(), event.aggregateId());
        return ProcessingOutcome.PROCESSED;
    }
}
