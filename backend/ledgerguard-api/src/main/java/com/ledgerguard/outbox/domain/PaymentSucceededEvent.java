package com.ledgerguard.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event emitted when a merchant payment is successfully executed and committed.
 */
public record PaymentSucceededEvent(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        PaymentSucceededPayload payload
) implements DomainEvent {
    public PaymentSucceededEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public static PaymentSucceededEvent of(
            UUID eventId,
            UUID paymentId,
            Instant occurredAt,
            PaymentSucceededPayload payload
    ) {
        return new PaymentSucceededEvent(
                eventId,
                "PAYMENT",
                paymentId,
                "PAYMENT_SUCCEEDED",
                1,
                occurredAt,
                payload
        );
    }
}
