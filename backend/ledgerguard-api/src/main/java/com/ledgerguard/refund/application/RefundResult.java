package com.ledgerguard.refund.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of refund processing.
 */
public record RefundResult(
        UUID refundId,
        UUID paymentId,
        long refundAmountMinor,
        long merchantDebitAmountMinor,
        long feeDebitAmountMinor,
        String currency,
        UUID journalTransactionId,
        Instant createdAt,
        boolean replayed
) {
}
