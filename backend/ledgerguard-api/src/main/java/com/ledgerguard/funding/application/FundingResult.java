package com.ledgerguard.funding.application;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Result model returned by the application funding service.
 */
public record FundingResult(
        UUID fundingId,
        FundingStatus status,
        long amountMinor,
        String currency,
        UUID providerOperationId,
        UUID journalTransactionId,
        Instant createdAt,
        Instant completedAt,
        boolean replayed
) {
    public static FundingResult from(FundingOperation funding, boolean replayed) {
        return new FundingResult(
                funding.getId(),
                funding.getStatus(),
                funding.getAmountMinor(),
                funding.getCurrency(),
                funding.getProviderOperationId(),
                funding.getJournalTransactionId(),
                funding.getCreatedAt(),
                funding.getCompletedAt(),
                replayed
        );
    }
}
