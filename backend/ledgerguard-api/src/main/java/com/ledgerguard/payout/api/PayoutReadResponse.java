package com.ledgerguard.payout.api;

import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import java.time.Instant;
import java.util.UUID;

public record PayoutReadResponse(
        UUID payoutId, String amountMinor, String currency, PayoutStatus status,
        UUID balanceHoldId, UUID providerOperationId, UUID journalTransactionId,
        Instant createdAt, Instant completedAt
) {
    public static PayoutReadResponse from(Payout operation) {
        return new PayoutReadResponse(operation.getId(), Long.toString(operation.getAmountMinor()),
                operation.getCurrency(), operation.getStatus(),
                operation.getBalanceHoldId(), operation.getProviderOperationId(), operation.getJournalTransactionId(),
                operation.getCreatedAt(), operation.getCompletedAt());
    }
}
