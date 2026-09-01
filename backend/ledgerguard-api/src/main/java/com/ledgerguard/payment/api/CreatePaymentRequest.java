package com.ledgerguard.payment.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Request payload for creating an internal merchant payment.
 */
public record CreatePaymentRequest(
        @NotNull(message = "merchantLedgerAccountId is required")
        UUID merchantLedgerAccountId,

        @NotNull(message = "amountMinor is required")
        @Positive(message = "amountMinor must be strictly positive")
        Long amountMinor
) {
}
