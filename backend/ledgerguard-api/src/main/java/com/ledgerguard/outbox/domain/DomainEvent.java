package com.ledgerguard.outbox.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Common abstraction representing an internal domain event intended for transactional outbox persistence.
 */
public interface DomainEvent {
    UUID eventId();
    String aggregateType();
    UUID aggregateId();
    String eventType();
    int eventVersion();
    Instant occurredAt();
    Object payload();
}
