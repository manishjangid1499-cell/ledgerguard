package com.ledgerguard.refund.api;

import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.payment.domain.PaymentValidationException;
import com.ledgerguard.refund.application.CreateRefundCommand;
import com.ledgerguard.refund.application.RefundResult;
import com.ledgerguard.refund.application.RefundService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for merchant payment refunds.
 */
@RestController
@RequestMapping("/api/payments")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping(value = "/{paymentId}/refund", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<RefundResponse> createRefund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("paymentId") UUID paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new PaymentValidationException("Idempotency-Key header is required, must not be blank, and must be at most 128 characters");
        }

        UUID actorUserId = UUID.fromString(jwt.getSubject());

        CreateRefundCommand command = new CreateRefundCommand(
                actorUserId,
                idempotencyKey,
                paymentId,
                Money.ofMinor(request.amountMinor(), "INR")
        );

        RefundResult result = refundService.createRefund(command);

        RefundResponse response = new RefundResponse(
                result.refundId(),
                result.paymentId(),
                String.valueOf(result.refundAmountMinor()),
                String.valueOf(result.merchantDebitAmountMinor()),
                String.valueOf(result.feeDebitAmountMinor()),
                result.currency(),
                result.journalTransactionId(),
                result.createdAt(),
                result.replayed()
        );

        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
