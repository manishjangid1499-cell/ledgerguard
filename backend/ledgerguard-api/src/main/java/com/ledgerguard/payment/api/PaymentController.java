package com.ledgerguard.payment.api;

import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.payment.application.PaymentQueryService;
import com.ledgerguard.shared.api.PagedResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import com.ledgerguard.payment.application.CreatePaymentCommand;
import com.ledgerguard.payment.application.PaymentResult;
import com.ledgerguard.payment.application.PaymentService;
import com.ledgerguard.payment.domain.PaymentValidationException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for customer merchant payment execution.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentQueryService queryService;

    public PaymentController(PaymentService paymentService, PaymentQueryService queryService) {
        this.paymentService = paymentService;
        this.queryService = queryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public PagedResponse<PaymentSummaryResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return queryService.list(UUID.fromString(jwt.getSubject()),
                UserRole.valueOf(jwt.getClaimAsString("role")), page, size);
    }

    @GetMapping(value = "/{paymentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public ResponseEntity<PaymentDetailResponse> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("paymentId") UUID paymentId,
            @RequestParam(name = "refundPage", defaultValue = "0") int refundPage,
            @RequestParam(name = "refundSize", defaultValue = "20") int refundSize) {
        return ResponseEntity.of(queryService.detail(UUID.fromString(jwt.getSubject()),
                UserRole.valueOf(jwt.getClaimAsString("role")), paymentId, refundPage, refundSize));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<PaymentResponse> createPayment(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new PaymentValidationException("Idempotency-Key header is required, must not be blank, and must be at most 128 characters");
        }

        UUID actorUserId = UUID.fromString(jwt.getSubject());

        CreatePaymentCommand command = new CreatePaymentCommand(
                actorUserId,
                idempotencyKey.trim(),
                request.merchantLedgerAccountId(),
                Money.ofMinor(request.amountMinor(), "INR")
        );

        PaymentResult result = paymentService.createPayment(command);

        PaymentResponse response = new PaymentResponse(
                result.paymentId(),
                result.customerLedgerAccountId(),
                result.merchantLedgerAccountId(),
                String.valueOf(result.grossAmountMinor()),
                String.valueOf(result.feeAmountMinor()),
                String.valueOf(result.merchantNetAmountMinor()),
                result.currency(),
                result.status(),
                result.journalTransactionId(),
                result.createdAt(),
                result.completedAt(),
                result.replayed()
        );

        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
