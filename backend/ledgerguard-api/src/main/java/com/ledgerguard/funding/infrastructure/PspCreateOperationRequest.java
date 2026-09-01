package com.ledgerguard.funding.infrastructure;

import java.util.UUID;

/**
 * Outbound HTTP payload for creating an operation at the external PSP simulator.
 */
public record PspCreateOperationRequest(
        UUID clientOperationId,
        String operationType,
        String amountMinor,
        String currency,
        String webhookUrl
) {
}
