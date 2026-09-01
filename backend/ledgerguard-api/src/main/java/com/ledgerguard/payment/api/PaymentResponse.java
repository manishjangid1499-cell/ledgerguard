package com.ledgerguard.payment.api;

import com.ledgerguard.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public response model for an internal merchant payment.
 * All monetary minor unit fields are serialized as decimal JSON strings to protect against JS precision loss.
 */
public record PaymentResponse(
        UUID paymentId,
        UUID customerLedgerAccountId,
        UUID merchantLedgerAccountId,
        String grossAmountMinor,
        String feeAmountMinor,
        String merchantNetAmountMinor,
        String currency,
        PaymentStatus status,
        UUID journalTransactionId,
        Instant createdAt,
        Instant completedAt,
        boolean replayed
) {
    public PaymentResponse {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(customerLedgerAccountId, "customerLedgerAccountId must not be null");
        Objects.requireNonNull(merchantLedgerAccountId, "merchantLedgerAccountId must not be null");
        Objects.requireNonNull(grossAmountMinor, "grossAmountMinor must not be null");
        Objects.requireNonNull(feeAmountMinor, "feeAmountMinor must not be null");
        Objects.requireNonNull(merchantNetAmountMinor, "merchantNetAmountMinor must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
