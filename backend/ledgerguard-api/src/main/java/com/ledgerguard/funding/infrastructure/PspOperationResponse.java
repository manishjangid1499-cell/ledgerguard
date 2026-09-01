package com.ledgerguard.funding.infrastructure;

import java.util.UUID;

/**
 * Inbound HTTP response contract from the external PSP simulator.
 */
public record PspOperationResponse(
        UUID providerOperationId,
        UUID clientOperationId,
        String operationType,
        String amountMinor,
        String currency,
        String status,
        String createdAt,
        String completedAt,
        boolean replayed
) {
}
