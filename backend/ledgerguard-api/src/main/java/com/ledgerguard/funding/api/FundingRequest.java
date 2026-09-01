package com.ledgerguard.funding.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Public HTTP request payload for initiating an external customer wallet funding.
 */
public record FundingRequest(
        @NotBlank(message = "amountMinor must not be blank")
        String amountMinor
) {
}
