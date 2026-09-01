package com.ledgerguard.payment.application;

import com.ledgerguard.payment.domain.PaymentStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of creating or replaying an internal merchant payment.
 */
public record PaymentResult(
        UUID paymentId,
        UUID customerLedgerAccountId,
        UUID merchantLedgerAccountId,
        long grossAmountMinor,
        long feeAmountMinor,
        long merchantNetAmountMinor,
        String currency,
        PaymentStatus status,
        UUID journalTransactionId,
        Instant createdAt,
        Instant completedAt,
        boolean replayed
) {
    public PaymentResult {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(customerLedgerAccountId, "customerLedgerAccountId must not be null");
        Objects.requireNonNull(merchantLedgerAccountId, "merchantLedgerAccountId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
