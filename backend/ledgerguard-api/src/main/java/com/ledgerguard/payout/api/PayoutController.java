package com.ledgerguard.payout.api;

import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.payout.application.CreatePayoutCommand;
import com.ledgerguard.payout.application.PayoutResult;
import com.ledgerguard.payout.application.PayoutService;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.domain.PayoutValidationException;
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

import java.math.BigInteger;
import java.util.UUID;

@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

    private final PayoutService payoutService;

    public PayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public ResponseEntity<PayoutResponse> requestPayout(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PayoutRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new PayoutValidationException("Idempotency-Key header is required, must not be blank, and must be at most 128 characters");
        }

        UUID actorUserId = UUID.fromString(jwt.getSubject());
        long amountMinor = parseAmount(request.amountMinor());

        CreatePayoutCommand command = new CreatePayoutCommand(
                actorUserId,
                idempotencyKey.trim(),
                Money.inr(amountMinor)
        );

        PayoutResult result = payoutService.requestPayout(command);
        PayoutResponse response = PayoutResponse.fromResult(result);

        if (result.replayed()) {
            if (result.status() == PayoutStatus.PROCESSING) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
            }
            return ResponseEntity.ok(response);
        }

        if (result.status() == PayoutStatus.SUCCEEDED || result.status() == PayoutStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private long parseAmount(String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            throw new PayoutValidationException("amountMinor must not be blank");
        }
        try {
            BigInteger bigInt = new BigInteger(amountStr.trim());
            if (bigInt.compareTo(BigInteger.ZERO) <= 0) {
                throw new PayoutValidationException("amountMinor must be strictly positive");
            }
            if (bigInt.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new PayoutValidationException("amountMinor exceeds maximum allowed value");
            }
            return bigInt.longValueExact();
        } catch (NumberFormatException ex) {
            throw new PayoutValidationException("amountMinor must be a valid integer string: " + amountStr);
        }
    }
}
