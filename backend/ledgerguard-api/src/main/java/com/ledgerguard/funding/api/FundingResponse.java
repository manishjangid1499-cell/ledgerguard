package com.ledgerguard.funding.api;

import com.ledgerguard.funding.application.FundingResult;

import java.util.UUID;

/**
 * Standardized HTTP response payload for external wallet funding operations.
 */
public record FundingResponse(
        UUID fundingId,
        String status,
        String amountMinor,
        String currency,
        UUID providerOperationId,
        UUID journalTransactionId,
        String createdAt,
        String completedAt,
        boolean replayed
) {
    public static FundingResponse from(FundingResult result) {
        return new FundingResponse(
                result.fundingId(),
                result.status().name(),
                String.valueOf(result.amountMinor()),
                result.currency(),
                result.providerOperationId(),
                result.journalTransactionId(),
                result.createdAt() != null ? result.createdAt().toString() : null,
                result.completedAt() != null ? result.completedAt().toString() : null,
                result.replayed()
        );
    }
}
