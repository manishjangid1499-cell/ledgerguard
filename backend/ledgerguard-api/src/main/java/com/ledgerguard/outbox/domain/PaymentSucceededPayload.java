package com.ledgerguard.outbox.domain;

import java.util.Objects;

/**
 * Immutable payload record for PAYMENT_SUCCEEDED domain events.
 * Monetary amounts are serialized as decimal strings for precision safety.
 */
public record PaymentSucceededPayload(
        String paymentId,
        String customerLedgerAccountId,
        String merchantLedgerAccountId,
        String grossAmountMinor,
        String feeAmountMinor,
        String merchantNetAmountMinor,
        String currency,
        String journalTransactionId
) {
    public PaymentSucceededPayload {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(customerLedgerAccountId, "customerLedgerAccountId must not be null");
        Objects.requireNonNull(merchantLedgerAccountId, "merchantLedgerAccountId must not be null");
        Objects.requireNonNull(grossAmountMinor, "grossAmountMinor must not be null");
        Objects.requireNonNull(feeAmountMinor, "feeAmountMinor must not be null");
        Objects.requireNonNull(merchantNetAmountMinor, "merchantNetAmountMinor must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(journalTransactionId, "journalTransactionId must not be null");
    }
}
