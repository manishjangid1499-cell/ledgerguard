package com.ledgerguard.notification.application;

import com.ledgerguard.notification.domain.IncomingDomainEvent;
import com.ledgerguard.notification.domain.NotificationDelivery;
import com.ledgerguard.notification.infrastructure.NotificationDeliveryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class NotificationDeliveryService {

    private static final String STATUS_DELIVERED = "DELIVERED";

    private final NotificationDeliveryRepository deliveryRepository;

    public NotificationDeliveryService(NotificationDeliveryRepository deliveryRepository) {
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository, "deliveryRepository must not be null");
    }

    /**
     * Persists a durable notification delivery record for the given domain event.
     *
     * @param event the validated incoming domain event
     * @return the created NotificationDelivery entity
     */
    public NotificationDelivery recordDelivery(IncomingDomainEvent event) {
        NotificationDelivery delivery = new NotificationDelivery(
                UUID.randomUUID(),
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                STATUS_DELIVERED,
                Instant.now()
        );
        return deliveryRepository.save(delivery);
    }
}
