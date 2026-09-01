package com.ledgerguard.psp.api;

import com.ledgerguard.psp.domain.ProviderOperation;

import java.time.Instant;
import java.util.UUID;

public record OperationResponse(
        UUID providerOperationId,
        UUID clientOperationId,
        String operationType,
        String status,
        String amountMinor,
        String currency,
        Instant createdAt,
        Instant completedAt
) {
    public static OperationResponse from(ProviderOperation op) {
        return new OperationResponse(
                op.getId(),
                op.getClientOperationId(),
                op.getOperationType().name(),
                op.getStatus().name(),
                String.valueOf(op.getAmountMinor()),
                op.getCurrency(),
                op.getCreatedAt(),
                op.getCompletedAt()
        );
    }
}
