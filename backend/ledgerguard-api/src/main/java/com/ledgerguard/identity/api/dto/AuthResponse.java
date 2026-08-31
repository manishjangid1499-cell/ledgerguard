package com.ledgerguard.identity.api.dto;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummaryResponse user
) {
    public static AuthResponse of(String accessToken, long expiresIn, UserSummaryResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresIn, user);
    }
}
