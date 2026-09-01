package com.ledgerguard.psp.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateOperationRequest(
        @NotNull(message = "clientOperationId is required")
        UUID clientOperationId,

        @NotBlank(message = "operationType is required")
        String operationType,

        @NotBlank(message = "amountMinor is required")
        String amountMinor,

        @NotBlank(message = "currency is required")
        String currency,

        String webhookUrl
) {}
