package com.ledgerguard.transfer.api;

import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.transfer.application.CreateTransferCommand;
import com.ledgerguard.transfer.application.TransferResult;
import com.ledgerguard.transfer.application.TransferService;
import com.ledgerguard.transfer.domain.TransferValidationException;
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
 * REST controller exposing internal wallet transfer operations.
 */
@RestController
@RequestMapping(value = "/api/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public ResponseEntity<TransferResponse> createTransfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new TransferValidationException("Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > 128) {
            throw new TransferValidationException("Idempotency-Key length must not exceed 128 characters");
        }

        UUID actorUserId;
        try {
            actorUserId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new TransferValidationException("Invalid authenticated actor principal subject");
        }

        CreateTransferCommand command = CreateTransferCommand.of(
                actorUserId,
                request.destinationLedgerAccountId(),
                Money.inr(request.amountMinor()),
                idempotencyKey
        );

        TransferResult result = transferService.createTransfer(command);

        TransferResponse response = new TransferResponse(
                result.transferId(),
                result.sourceLedgerAccountId(),
                result.destinationLedgerAccountId(),
                result.amountMinor(),
                result.currency(),
                result.journalTransactionId(),
                result.createdAt(),
                result.replayed()
        );

        if (result.replayed()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
    }
}
