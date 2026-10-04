package com.ledgerguard.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank(message = "Reset token is required.")
        String token,

        @NotBlank(message = "Password is required.")
        String newPassword
) {}
