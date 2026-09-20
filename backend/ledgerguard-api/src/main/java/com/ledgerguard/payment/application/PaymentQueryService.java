package com.ledgerguard.payment.application;

import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.payment.api.PaymentDetailResponse;
import com.ledgerguard.payment.api.PaymentSummaryResponse;
import com.ledgerguard.payment.domain.Payment;
import com.ledgerguard.payment.domain.PaymentStatus;
import com.ledgerguard.payment.infrastructure.PaymentRepository;
import com.ledgerguard.refund.api.RefundSummaryResponse;
import com.ledgerguard.refund.infrastructure.RefundRepository;
import com.ledgerguard.shared.api.PagedResponse;
import com.ledgerguard.shared.api.QueryPagination;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentQueryService {
    private final PaymentRepository payments;
    private final RefundRepository refunds;

    public PaymentQueryService(PaymentRepository payments, RefundRepository refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    public PagedResponse<PaymentSummaryResponse> list(UUID userId, UserRole role, int page, int size) {
        var pageable = QueryPagination.newestFirst(page, size);
        var result = switch (role) {
            case CUSTOMER -> payments.findByCustomerUserId(userId, pageable);
            case MERCHANT -> payments.findReceivedByUserId(userId, pageable);
            default -> throw new AccessDeniedException("Financial account required");
        };
        return QueryPagination.response(result.map(PaymentSummaryResponse::from));
    }

    public Optional<PaymentDetailResponse> detail(UUID userId, UserRole role, UUID id, int refundPage, int refundSize) {
        Optional<Payment> payment = switch (role) {
            case CUSTOMER -> payments.findByIdAndCustomerUserId(id, userId);
            case MERCHANT -> payments.findReceivedByIdAndUserId(id, userId);
            default -> throw new AccessDeniedException("Financial account required");
        };
        // Authorize the parent before looking up any refund data.
        return payment.map(value -> {
            long refunded = refunds.sumRefundAmountByPaymentId(id);
            long remaining = value.getStatus() == PaymentStatus.SUCCEEDED
                    ? Math.subtractExact(value.getGrossAmountMinor(), refunded) : 0;
            var page = refunds.findByPaymentId(id, QueryPagination.newestFirst(refundPage, refundSize));
            return new PaymentDetailResponse(PaymentSummaryResponse.from(value), Long.toString(refunded),
                    Long.toString(remaining), QueryPagination.response(page.map(RefundSummaryResponse::from)));
        });
    }
}
