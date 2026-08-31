package com.ledgerguard.transfer.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Public HTTP request payload for initiating an internal transfer.
 * Note: Source wallet and actor identity are strictly derived server-side from JWT authentication.
 */
public record CreateTransferRequest(
        @NotNull(message = "destinationLedgerAccountId is required")
        UUID destinationLedgerAccountId,

        @NotNull(message = "amountMinor is required")
        @Positive(message = "amountMinor must be strictly positive")
        Long amountMinor
) {
}
