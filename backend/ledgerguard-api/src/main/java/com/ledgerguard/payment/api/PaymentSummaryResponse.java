package com.ledgerguard.payment.api;

import com.ledgerguard.payment.domain.Payment;
import com.ledgerguard.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentSummaryResponse(
        UUID paymentId, UUID customerLedgerAccountId, UUID merchantLedgerAccountId,
        String grossAmountMinor, String feeAmountMinor, String merchantNetAmountMinor,
        String currency, PaymentStatus status, UUID journalTransactionId, Instant createdAt, Instant completedAt,
        String refundedAmountMinor, String refundStatus
) {
    public static PaymentSummaryResponse from(Payment payment) {
        return from(payment, 0L);
    }

    public static PaymentSummaryResponse from(Payment payment, long refundedAmountMinor) {
        String statusStr;
        if (refundedAmountMinor <= 0) {
            statusStr = "NOT_REFUNDED";
        } else if (refundedAmountMinor >= payment.getGrossAmountMinor()) {
            statusStr = "FULLY_REFUNDED";
        } else {
            statusStr = "PARTIALLY_REFUNDED";
        }
        return new PaymentSummaryResponse(
                payment.getId(), payment.getCustomerLedgerAccountId(),
                payment.getMerchantLedgerAccountId(), Long.toString(payment.getGrossAmountMinor()),
                Long.toString(payment.getFeeAmountMinor()), Long.toString(payment.getMerchantNetAmountMinor()),
                payment.getCurrency(), payment.getStatus(), payment.getJournalTransactionId(),
                payment.getCreatedAt(), payment.getCompletedAt(),
                Long.toString(refundedAmountMinor), statusStr
        );
    }
}
