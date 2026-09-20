package com.ledgerguard.funding.api;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import java.time.Instant;
import java.util.UUID;

public record FundingReadResponse(
        UUID fundingId, String amountMinor, String currency, FundingStatus status,
         UUID providerOperationId, UUID journalTransactionId,
        Instant createdAt, Instant completedAt
) {
    public static FundingReadResponse from(FundingOperation operation) {
        return new FundingReadResponse(operation.getId(), Long.toString(operation.getAmountMinor()),
                operation.getCurrency(), operation.getStatus(),
                operation.getProviderOperationId(), operation.getJournalTransactionId(),
                operation.getCreatedAt(), operation.getCompletedAt());
    }
}
