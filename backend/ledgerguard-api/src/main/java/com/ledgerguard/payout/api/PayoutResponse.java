package com.ledgerguard.payout.api;

import com.ledgerguard.payout.application.PayoutResult;

import java.time.Instant;
import java.util.UUID;

public record PayoutResponse(
        UUID payoutId,
        String status,
        String amountMinor,
        String currency,
        UUID balanceHoldId,
        UUID providerOperationId,
        UUID journalTransactionId,
        Instant createdAt,
        Instant completedAt,
        boolean replayed
) {
    public static PayoutResponse fromResult(PayoutResult result) {
        return new PayoutResponse(
                result.payoutId(),
                result.status().name(),
                result.amountMinor(),
                result.currency(),
                result.balanceHoldId(),
                result.providerOperationId(),
                result.journalTransactionId(),
                result.createdAt(),
                result.completedAt(),
                result.replayed()
        );
    }
}
