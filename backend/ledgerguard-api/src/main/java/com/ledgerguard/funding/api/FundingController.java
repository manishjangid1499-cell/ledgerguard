package com.ledgerguard.funding.api;

import com.ledgerguard.funding.application.CreateFundingCommand;
import com.ledgerguard.funding.application.FundingResult;
import com.ledgerguard.funding.application.FundingService;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.domain.FundingValidationException;
import com.ledgerguard.ledger.domain.Money;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.UUID;

/**
 * REST controller for customer external wallet funding (top-ups).
 */
@RestController
@RequestMapping("/api/funding")
public class FundingController {

    private final FundingService fundingService;

    public FundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<FundingResponse> fundWallet(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FundingRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new FundingValidationException("Idempotency-Key header is required, must not be blank, and must be at most 128 characters");
        }

        UUID actorUserId = UUID.fromString(jwt.getSubject());
        long amountMinor = parseAmount(request.amountMinor());

        CreateFundingCommand command = new CreateFundingCommand(
                actorUserId,
                idempotencyKey.trim(),
                Money.inr(amountMinor)
        );

        FundingResult result = fundingService.fundWallet(command);
        FundingResponse response = FundingResponse.from(result);

        if (result.status() == FundingStatus.CREATED
                || result.status() == FundingStatus.PROCESSING
                || result.status() == FundingStatus.UNKNOWN
                || result.status() == FundingStatus.RECONCILIATION_REQUIRED) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }

        if (result.replayed()) {
            return ResponseEntity.ok(response);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private long parseAmount(String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            throw new FundingValidationException("amountMinor must not be blank");
        }
        try {
            BigInteger bi = new BigInteger(amountStr.trim());
            long val = bi.longValueExact();
            if (val <= 0) {
                throw new FundingValidationException("amountMinor must be strictly positive: " + amountStr);
            }
            return val;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new FundingValidationException("amountMinor must be a valid strictly positive 64-bit integer: " + amountStr);
        }
    }
}
