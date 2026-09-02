package com.ledgerguard.provider.domain;

import java.time.Instant;
import java.util.UUID;

public record ProviderEventPayload(
        UUID eventId,
        long eventSequence,
        String eventType,
        UUID providerOperationId,
        UUID clientOperationId,
        String operationType,
        String status,
        String amountMinor,
        String currency,
        Instant occurredAt,
        String rawJson
) {}
