package com.ledgerguard.outbox.application;

import com.ledgerguard.outbox.domain.DomainEvent;
import com.ledgerguard.outbox.domain.OutboxEvent;
import com.ledgerguard.outbox.infrastructure.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;

/**
 * Application service for appending domain events to the transactional outbox table.
 * All appends MUST execute within an existing caller database transaction (MANDATORY propagation).
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository, "outboxEventRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(DomainEvent event) {
        Objects.requireNonNull(event, "DomainEvent must not be null");

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(event.payload());
            JsonNode tree = objectMapper.readTree(jsonPayload);
            if (!tree.isObject()) {
                throw new IllegalArgumentException("Domain event payload must serialize to a JSON object: " + event.payload());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize domain event payload for eventId: " + event.eventId(), e);
        }

        OutboxEvent outboxEvent = OutboxEvent.pending(
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                jsonPayload,
                event.occurredAt(),
                Instant.now()
        );

        outboxEventRepository.save(outboxEvent);
    }
}
