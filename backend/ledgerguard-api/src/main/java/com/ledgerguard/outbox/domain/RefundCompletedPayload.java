package com.ledgerguard.outbox.domain;

import java.util.Objects;

/**
 * Immutable payload record for REFUND_COMPLETED domain events.
 * Monetary amounts are serialized as decimal strings for precision safety.
 */
public record RefundCompletedPayload(
        String refundId,
        String paymentId,
        String refundAmountMinor,
        String merchantDebitAmountMinor,
        String feeDebitAmountMinor,
        String currency,
        String journalTransactionId
) {
    public RefundCompletedPayload {
        Objects.requireNonNull(refundId, "refundId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(refundAmountMinor, "refundAmountMinor must not be null");
        Objects.requireNonNull(merchantDebitAmountMinor, "merchantDebitAmountMinor must not be null");
        Objects.requireNonNull(feeDebitAmountMinor, "feeDebitAmountMinor must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(journalTransactionId, "journalTransactionId must not be null");
    }
}
