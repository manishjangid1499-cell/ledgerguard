package com.ledgerguard.refund.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload for refund execution.
 * Monetary amounts are serialized as decimal JSON strings to preserve 64-bit precision in JavaScript clients.
 */
public record RefundResponse(
        UUID refundId,
        UUID paymentId,
        String refundAmountMinor,
        String merchantDebitAmountMinor,
        String feeDebitAmountMinor,
        String currency,
        UUID journalTransactionId,
        Instant createdAt,
        boolean replayed
) {
}
