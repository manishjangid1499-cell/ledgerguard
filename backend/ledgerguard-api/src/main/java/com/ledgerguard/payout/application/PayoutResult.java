package com.ledgerguard.payout.application;

import com.ledgerguard.payout.domain.PayoutStatus;

import java.time.Instant;
import java.util.UUID;

public record PayoutResult(
        UUID payoutId,
        PayoutStatus status,
        String amountMinor,
        String currency,
        UUID balanceHoldId,
        UUID providerOperationId,
        UUID journalTransactionId,
        Instant createdAt,
        Instant completedAt,
        boolean replayed
) {
}
