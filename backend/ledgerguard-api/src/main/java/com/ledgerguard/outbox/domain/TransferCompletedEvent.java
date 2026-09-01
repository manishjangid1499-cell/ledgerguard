package com.ledgerguard.outbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event emitted when an internal wallet transfer is successfully executed and committed.
 */
public record TransferCompletedEvent(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        TransferCompletedPayload payload
) implements DomainEvent {
    public TransferCompletedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public static TransferCompletedEvent of(
            UUID eventId,
            UUID transferId,
            Instant occurredAt,
            TransferCompletedPayload payload
    ) {
        return new TransferCompletedEvent(
                eventId,
                "TRANSFER",
                transferId,
                "TRANSFER_COMPLETED",
                1,
                occurredAt,
                payload
        );
    }
}
