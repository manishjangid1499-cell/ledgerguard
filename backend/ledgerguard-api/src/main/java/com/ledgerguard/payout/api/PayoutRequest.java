package com.ledgerguard.payout.api;

import jakarta.validation.constraints.NotBlank;

public record PayoutRequest(
        @NotBlank(message = "amountMinor is required and must not be blank")
        String amountMinor
) {
}
