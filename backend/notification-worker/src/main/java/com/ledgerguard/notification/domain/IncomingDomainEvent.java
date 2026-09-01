package com.ledgerguard.notification.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IncomingDomainEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        JsonNode dataNode
) {
    public IncomingDomainEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(dataNode, "dataNode must not be null");
    }
}
