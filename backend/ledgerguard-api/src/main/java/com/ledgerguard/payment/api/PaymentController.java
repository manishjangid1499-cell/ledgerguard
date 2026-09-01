package com.ledgerguard.payment.api;

import com.ledgerguard.ledger.domain.Money;
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

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
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
