package com.ledgerguard.payout.api;

import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.payout.application.PayoutQueryService;
import com.ledgerguard.shared.api.PagedResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final PayoutQueryService queryService;

    public PayoutController(PayoutService payoutService, PayoutQueryService queryService) {
        this.payoutService = payoutService;
        this.queryService = queryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public PagedResponse<PayoutReadResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return queryService.list(UUID.fromString(jwt.getSubject()), page, size);
    }

    @GetMapping(value = "/{payoutId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public ResponseEntity<PayoutReadResponse> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("payoutId") UUID payoutId) {
        return ResponseEntity.of(queryService.detail(UUID.fromString(jwt.getSubject()), payoutId));
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
            if (result.status() == PayoutStatus.CREATED
                    || result.status() == PayoutStatus.PROCESSING
                    || result.status() == PayoutStatus.UNKNOWN
                    || result.status() == PayoutStatus.RECONCILIATION_REQUIRED) {
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
