package com.ledgerguard.refund.api;

import com.ledgerguard.refund.domain.Refund;
import java.time.Instant;
import java.util.UUID;

public record RefundSummaryResponse(
        UUID refundId, String refundAmountMinor, String merchantDebitAmountMinor,
        String feeDebitAmountMinor, String currency, UUID journalTransactionId, Instant createdAt
) {
    public static RefundSummaryResponse from(Refund refund) {
        return new RefundSummaryResponse(refund.getId(), Long.toString(refund.getRefundAmountMinor()),
                Long.toString(refund.getMerchantDebitAmountMinor()), Long.toString(refund.getFeeDebitAmountMinor()),
                refund.getCurrency(), refund.getJournalTransactionId(), refund.getCreatedAt());
    }
}
