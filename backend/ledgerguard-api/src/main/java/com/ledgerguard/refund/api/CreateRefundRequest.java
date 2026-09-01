package com.ledgerguard.refund.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request payload for creating a partial or full payment refund.
 */
public record CreateRefundRequest(
        @NotNull(message = "amountMinor is required")
        @Positive(message = "amountMinor must be strictly positive")
        Long amountMinor
) {
}
