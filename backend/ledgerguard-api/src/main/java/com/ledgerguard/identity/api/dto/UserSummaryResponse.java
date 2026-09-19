package com.ledgerguard.identity.api.dto;

import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record UserSummaryResponse(
        UUID id,
        String fullName,
        String email,
        UserRole role,
        UserStatus status,
        Instant createdAt
) {
    public UserSummaryResponse(UUID id, String email, UserRole role, UserStatus status, Instant createdAt) {
        this(id, null, email, role, status, createdAt);
    }

    public static UserSummaryResponse from(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}
