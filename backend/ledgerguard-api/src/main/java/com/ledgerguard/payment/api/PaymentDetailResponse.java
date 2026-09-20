package com.ledgerguard.payment.api;

import com.ledgerguard.refund.api.RefundSummaryResponse;
import com.ledgerguard.shared.api.PagedResponse;

public record PaymentDetailResponse(
        PaymentSummaryResponse payment, String refundedAmountMinor, String refundableAmountMinor,
        PagedResponse<RefundSummaryResponse> refunds
) {}
