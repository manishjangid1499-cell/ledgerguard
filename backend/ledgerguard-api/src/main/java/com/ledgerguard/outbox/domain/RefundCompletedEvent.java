package com.ledgerguard.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event emitted when a payment refund is successfully executed and committed.
 */
public record RefundCompletedEvent(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        RefundCompletedPayload payload
) implements DomainEvent {
    public RefundCompletedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public static RefundCompletedEvent of(
            UUID eventId,
            UUID refundId,
            Instant occurredAt,
            RefundCompletedPayload payload
    ) {
        return new RefundCompletedEvent(
                eventId,
                "REFUND",
                refundId,
                "REFUND_COMPLETED",
                1,
                occurredAt,
                payload
        );
    }
}
