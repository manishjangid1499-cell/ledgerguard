package com.ledgerguard.reconciliation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManualResolveRequest(
        @NotBlank(message = "Resolution note must not be blank")
        @Size(max = 1000, message = "Resolution note must not exceed 1000 characters")
        String resolutionNote
) {
}
