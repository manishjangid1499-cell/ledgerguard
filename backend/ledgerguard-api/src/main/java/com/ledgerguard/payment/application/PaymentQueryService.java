package com.ledgerguard.payment.application;

import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.payment.api.MerchantSummaryResponse;
import com.ledgerguard.payment.api.PaymentDetailResponse;
import com.ledgerguard.payment.api.PaymentSummaryResponse;
import com.ledgerguard.payment.domain.Payment;
import com.ledgerguard.payment.domain.PaymentStatus;
import com.ledgerguard.payment.domain.PaymentValidationException;
import com.ledgerguard.payment.infrastructure.PaymentRepository;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.refund.api.RefundSummaryResponse;
import com.ledgerguard.refund.infrastructure.RefundRepository;
import com.ledgerguard.shared.api.PagedResponse;
import com.ledgerguard.shared.api.QueryPagination;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PaymentQueryService {
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PayoutRepository payouts;

    public PaymentQueryService(PaymentRepository payments, RefundRepository refunds, PayoutRepository payouts) {
        this.payments = payments;
        this.refunds = refunds;
        this.payouts = payouts;
    }

    public PagedResponse<PaymentSummaryResponse> list(UUID userId, UserRole role, int page, int size) {
        return list(userId, role, null, null, "newest", page, size);
    }

    public PagedResponse<PaymentSummaryResponse> list(
            UUID userId, UserRole role, UUID paymentId, PaymentStatus status, String sort, int page, int size
    ) {
        if (sort != null && !sort.isBlank() && !sort.equalsIgnoreCase("newest") && !sort.equalsIgnoreCase("oldest")) {
            throw new PaymentValidationException("Invalid sort parameter: '" + sort + "'. Permitted values are 'newest' or 'oldest'.");
        }

        var pageable = QueryPagination.of(page, size, sort);
        int resolvedPage = pageable.getPageNumber();
        int resolvedSize = pageable.getPageSize();

        if (paymentId != null) {
            Optional<Payment> single = switch (role) {
                case CUSTOMER -> payments.findByIdAndCustomerUserId(paymentId, userId);
                case MERCHANT -> payments.findReceivedByIdAndUserId(paymentId, userId);
                default -> throw new AccessDeniedException("Financial account required");
            };
            if (single.isEmpty()) {
                return new PagedResponse<>(Collections.emptyList(), resolvedPage, resolvedSize, 0, 0);
            }
            Payment p = single.get();
            if (status != null && p.getStatus() != status) {
                return new PagedResponse<>(Collections.emptyList(), resolvedPage, resolvedSize, 0, 0);
            }
            if (resolvedPage > 0) {
                return new PagedResponse<>(Collections.emptyList(), resolvedPage, resolvedSize, 1, 1);
            }
            long ref = refunds.sumRefundAmountByPaymentId(p.getId());
            return new PagedResponse<>(List.of(PaymentSummaryResponse.from(p, ref)), resolvedPage, resolvedSize, 1, 1);
        }
        Page<Payment> result;
        if (status != null) {
            result = switch (role) {
                case CUSTOMER -> payments.findByCustomerUserIdAndStatus(userId, status, pageable);
                case MERCHANT -> payments.findReceivedByUserIdAndStatus(userId, status, pageable);
                default -> throw new AccessDeniedException("Financial account required");
            };
        } else {
            result = switch (role) {
                case CUSTOMER -> payments.findByCustomerUserId(userId, pageable);
                case MERCHANT -> payments.findReceivedByUserId(userId, pageable);
                default -> throw new AccessDeniedException("Financial account required");
            };
        }

        List<UUID> paymentIds = result.getContent().stream().map(Payment::getId).toList();
        Map<UUID, Long> refundedMap = paymentIds.isEmpty() ? Map.of() :
                refunds.sumRefundAmountByPaymentIds(paymentIds).stream()
                        .collect(Collectors.toMap(
                                row -> (UUID) row[0],
                                row -> ((Number) row[1]).longValue()
                        ));

        return QueryPagination.response(result.map(p ->
                PaymentSummaryResponse.from(p, refundedMap.getOrDefault(p.getId(), 0L))));
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
            return new PaymentDetailResponse(PaymentSummaryResponse.from(value, refunded), Long.toString(refunded),
                    Long.toString(remaining), QueryPagination.response(page.map(RefundSummaryResponse::from)));
        });
    }

    public MerchantSummaryResponse getMerchantSummary(UUID userId) {
        var totals = payments.getReceivedPaymentTotalsByUserId(userId);
        long gross = 0L;
        long fees = 0L;
        long net = 0L;
        if (!totals.isEmpty() && totals.get(0) != null) {
            Object[] row = totals.get(0);
            gross = row[0] != null ? ((Number) row[0]).longValue() : 0L;
            fees = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            net = row[2] != null ? ((Number) row[2]).longValue() : 0L;
        }

        long refunded = refunds.sumTotalRefundedForMerchant(userId);

        var payoutSummary = payouts.getPendingPayoutSummaryByUserId(userId);
        long pendingCount = 0L;
        long pendingAmount = 0L;
        if (!payoutSummary.isEmpty() && payoutSummary.get(0) != null) {
            Object[] row = payoutSummary.get(0);
            pendingCount = row[0] != null ? ((Number) row[0]).longValue() : 0L;
            pendingAmount = row[1] != null ? ((Number) row[1]).longValue() : 0L;
        }

        return new MerchantSummaryResponse(
                Long.toString(gross),
                Long.toString(fees),
                Long.toString(net),
                Long.toString(refunded),
                pendingCount,
                Long.toString(pendingAmount),
                "INR"
        );
    }
}
