package com.ledgerguard.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a well-formed email address")
        String email,

        @NotBlank(message = "must not be blank")
        String password
) {}
