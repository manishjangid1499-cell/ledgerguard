package com.ledgerguard.outbox.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Structured CloudEvents 1.0 compatible JSON event envelope for domain events.
 */
public record DomainEventEnvelope(
        String specversion,
        UUID id,
        String source,
        String type,
        String subject,
        Instant time,
        String datacontenttype,
        int eventversion,
        String aggregatetype,
        UUID aggregateid,
        JsonNode data
) {
    public static final String SPEC_VERSION = "1.0";
    public static final String SOURCE_URI = "urn:ledgerguard:ledgerguard-api";
    public static final String DATA_CONTENT_TYPE = "application/json";

    public DomainEventEnvelope {
        Objects.requireNonNull(specversion, "specversion must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(datacontenttype, "datacontenttype must not be null");
        Objects.requireNonNull(aggregatetype, "aggregatetype must not be null");
        Objects.requireNonNull(aggregateid, "aggregateid must not be null");
        Objects.requireNonNull(data, "data must not be null");
    }

    public static DomainEventEnvelope from(OutboxEvent event, ObjectMapper objectMapper) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        JsonNode dataNode;
        try {
            dataNode = objectMapper.readTree(event.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse outbox payload for event " + event.getId(), e);
        }

        return new DomainEventEnvelope(
                SPEC_VERSION,
                event.getId(),
                SOURCE_URI,
                event.getEventType(),
                event.getAggregateType() + "/" + event.getAggregateId(),
                event.getOccurredAt(),
                DATA_CONTENT_TYPE,
                event.getEventVersion(),
                event.getAggregateType(),
                event.getAggregateId(),
                dataNode
        );
    }
}
