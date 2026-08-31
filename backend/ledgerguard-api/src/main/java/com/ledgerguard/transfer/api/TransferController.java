package com.ledgerguard.transfer.api;

import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.shared.api.PagedResponse;
import com.ledgerguard.transfer.application.CreateTransferCommand;
import com.ledgerguard.transfer.application.TransferQueryService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing internal wallet transfer operations and query endpoints.
 */
@RestController
@RequestMapping(value = "/api/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransferController {

    private final TransferService transferService;
    private final TransferQueryService transferQueryService;

    public TransferController(TransferService transferService, TransferQueryService transferQueryService) {
        this.transferService = transferService;
        this.transferQueryService = transferQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public ResponseEntity<PagedResponse<TransferSummaryResponse>> getTransfers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        UUID actorUserId = UUID.fromString(jwt.getSubject());
        PagedResponse<TransferSummaryResponse> pagedResponse = transferQueryService.findTransfersForUser(actorUserId, page, size);
        return ResponseEntity.ok(pagedResponse);
    }

    @GetMapping("/{transferId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT')")
    public ResponseEntity<TransferDetailResponse> getTransferDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("transferId") UUID transferId
    ) {
        UUID actorUserId = UUID.fromString(jwt.getSubject());
        return transferQueryService.findTransferDetailForUser(actorUserId, transferId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
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
