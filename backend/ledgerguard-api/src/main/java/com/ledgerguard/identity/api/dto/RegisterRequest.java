package com.ledgerguard.identity.api.dto;

import com.ledgerguard.identity.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "must not be blank")
        @Email(message = "must be a well-formed email address")
        String email,

        @NotBlank(message = "must not be blank")
        @Size(min = 12, max = 72, message = "password must be between 12 and 72 characters")
        String password,

        UserRole role
) {}
