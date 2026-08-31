package com.ledgerguard.identity.application;

import com.ledgerguard.identity.domain.RefreshToken;
import com.ledgerguard.identity.domain.RefreshTokenRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.shared.security.JwtProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
    }

    public record GeneratedToken(String rawToken, RefreshToken entity) {}

    public record RotatedToken(String rawToken, User user) {}

    @Transactional
    public GeneratedToken createRefreshToken(User user) {
        String rawToken = generateSecureRandomToken();
        String tokenHash = hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(jwtProperties.getRefreshTokenTtl());

        RefreshToken entity = RefreshToken.create(user, tokenHash, expiresAt);
        refreshTokenRepository.save(entity);

        return new GeneratedToken(rawToken, entity);
    }

    @Transactional
    public RotatedToken rotateRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidRefreshTokenException("Invalid refresh token.");
        }

        String tokenHash = hashToken(rawRefreshToken);
        Instant now = Instant.now();

        RefreshToken existingToken = refreshTokenRepository.findByTokenHashWithLock(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token."));

        if (!existingToken.isValid(now) || !existingToken.getUser().isActive()) {
            throw new InvalidRefreshTokenException("Invalid refresh token.");
        }

        // Revoke the old token
        existingToken.revoke(now);
        refreshTokenRepository.save(existingToken);

        // Issue and persist new refresh token for the same user
        User user = existingToken.getUser();
        String newRawToken = generateSecureRandomToken();
        String newTokenHash = hashToken(newRawToken);
        Instant newExpiresAt = now.plus(jwtProperties.getRefreshTokenTtl());

        RefreshToken newToken = RefreshToken.create(user, newTokenHash, newExpiresAt);
        refreshTokenRepository.save(newToken);

        return new RotatedToken(newRawToken, user);
    }

    @Transactional
    public void revokeToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }

        String tokenHash = hashToken(rawRefreshToken);
        Instant now = Instant.now();

        refreshTokenRepository.findByTokenHashWithLock(tokenHash)
                .ifPresent(token -> {
                    if (token.isValid(now)) {
                        token.revoke(now);
                        refreshTokenRepository.save(token);
                    }
                });
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private String generateSecureRandomToken() {
        byte[] randomBytes = new byte[32]; // 256 bits of entropy
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
