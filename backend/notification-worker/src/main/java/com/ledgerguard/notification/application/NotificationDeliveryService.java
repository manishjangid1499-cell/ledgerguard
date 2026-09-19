package com.ledgerguard.notification.application;

import com.ledgerguard.notification.delivery.EmailTemplateRenderer;
import com.ledgerguard.notification.domain.IncomingDomainEvent;
import com.ledgerguard.notification.domain.NotificationDelivery;
import com.ledgerguard.notification.domain.NotificationDeliveryStatus;
import com.ledgerguard.notification.infrastructure.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private static final String TYPE_TRANSFER_COMPLETED = "TRANSFER_COMPLETED";
    private static final String TYPE_PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    private static final String TYPE_REFUND_COMPLETED = "REFUND_COMPLETED";

    private final NotificationDeliveryRepository deliveryRepository;
    private final boolean emailEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationDeliveryService(
            NotificationDeliveryRepository deliveryRepository,
            @Value("${ledgerguard.notification.email.enabled:true}") boolean emailEnabled
    ) {
        this.deliveryRepository = Objects.requireNonNull(deliveryRepository, "deliveryRepository must not be null");
        this.emailEnabled = emailEnabled;
    }

    public NotificationDeliveryService(NotificationDeliveryRepository deliveryRepository) {
        this(deliveryRepository, true);
    }

    /**
     * Persists durable notification delivery records for the given domain event.
     * Generates recipient-specific records for v2 events or a legacy recorded row for v1 events.
     *
     * @param event the validated incoming domain event
     * @return the list of created NotificationDelivery entities
     */
    public List<NotificationDelivery> recordDelivery(IncomingDomainEvent event) {
        Objects.requireNonNull(event, "IncomingDomainEvent must not be null");
        Instant now = Instant.now();

        if (event.eventVersion() == 1) {
            NotificationDelivery legacy = new NotificationDelivery(
                    UUID.randomUUID(),
                    event.eventId(),
                    event.eventType(),
                    event.aggregateType(),
                    event.aggregateId(),
                    NotificationDeliveryStatus.LEGACY_RECORDED.name(),
                    now
            );
            return List.of(deliveryRepository.save(legacy));
        }

        List<NotificationDelivery> deliveries = buildDeliveriesForV2Event(event, now);
        List<NotificationDelivery> saved = new ArrayList<>(deliveries.size());
        for (NotificationDelivery delivery : deliveries) {
            saved.add(deliveryRepository.save(delivery));
        }
        return saved;
    }

    private List<NotificationDelivery> buildDeliveriesForV2Event(IncomingDomainEvent event, Instant now) {
        JsonNode data = event.dataNode();
        String payloadJson = data.toString();
        List<NotificationDelivery> list = new ArrayList<>(2);

        switch (event.eventType()) {
            case TYPE_TRANSFER_COMPLETED -> {
                // 1. Sender delivery
                UUID sourceUserId = parseUuidSafe(data.path("sourceUserId").asText());
                String sourceEmail = data.path("sourceEmail").asText();
                list.add(createDelivery(
                        event, sourceUserId, sourceEmail,
                        EmailTemplateRenderer.TEMPLATE_TRANSFER_SENDER,
                        "LedgerGuard transfer completed",
                        payloadJson, now
                ));

                // 2. Receiver delivery
                UUID destUserId = parseUuidSafe(data.path("destinationUserId").asText());
                String destEmail = data.path("destinationEmail").asText();
                list.add(createDelivery(
                        event, destUserId, destEmail,
                        EmailTemplateRenderer.TEMPLATE_TRANSFER_RECEIVER,
                        "You received money on LedgerGuard",
                        payloadJson, now
                ));
            }
            case TYPE_PAYMENT_SUCCEEDED -> {
                // 1. Customer delivery
                UUID customerUserId = parseUuidSafe(data.path("customerUserId").asText());
                String customerEmail = data.path("customerEmail").asText();
                list.add(createDelivery(
                        event, customerUserId, customerEmail,
                        EmailTemplateRenderer.TEMPLATE_PAYMENT_CUSTOMER,
                        "LedgerGuard payment completed",
                        payloadJson, now
                ));

                // 2. Merchant delivery
                UUID merchantUserId = parseUuidSafe(data.path("merchantUserId").asText());
                String merchantEmail = data.path("merchantEmail").asText();
                list.add(createDelivery(
                        event, merchantUserId, merchantEmail,
                        EmailTemplateRenderer.TEMPLATE_PAYMENT_MERCHANT,
                        "You received a customer payment",
                        payloadJson, now
                ));
            }
            case TYPE_REFUND_COMPLETED -> {
                // 1. Customer delivery
                UUID customerUserId = parseUuidSafe(data.path("customerUserId").asText());
                String customerEmail = data.path("customerEmail").asText();
                list.add(createDelivery(
                        event, customerUserId, customerEmail,
                        EmailTemplateRenderer.TEMPLATE_REFUND_CUSTOMER,
                        "LedgerGuard refund completed",
                        payloadJson, now
                ));

                // 2. Merchant delivery
                UUID merchantUserId = parseUuidSafe(data.path("merchantUserId").asText());
                String merchantEmail = data.path("merchantEmail").asText();
                list.add(createDelivery(
                        event, merchantUserId, merchantEmail,
                        EmailTemplateRenderer.TEMPLATE_REFUND_MERCHANT,
                        "LedgerGuard refund issued",
                        payloadJson, now
                ));
            }
            default -> log.warn("Unrecognized event type in delivery generation: {}", event.eventType());
        }

        return list;
    }

    private NotificationDelivery createDelivery(
            IncomingDomainEvent event,
            UUID recipientUserId,
            String recipientEmail,
            String templateKey,
            String subject,
            String payloadJson,
            Instant now
    ) {
        UUID deliveryId = UUID.randomUUID();
        if (!emailEnabled) {
            return NotificationDelivery.skipped(
                    deliveryId,
                    event.eventId(),
                    event.eventType(),
                    event.aggregateType(),
                    event.aggregateId(),
                    recipientUserId,
                    recipientEmail,
                    "EMAIL",
                    templateKey,
                    subject,
                    payloadJson,
                    now
            );
        }

        return NotificationDelivery.pending(
                deliveryId,
                event.eventId(),
                event.eventType(),
                event.aggregateType(),
                event.aggregateId(),
                recipientUserId,
                recipientEmail,
                "EMAIL",
                templateKey,
                subject,
                payloadJson,
                now
        );
    }

    private UUID parseUuidSafe(String text) {
        try {
            return (text != null && !text.isBlank()) ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
