package com.ledgerguard.identity.application;

import com.ledgerguard.identity.domain.RefreshToken;
import com.ledgerguard.identity.domain.RefreshTokenRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
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
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               UserRepository userRepository,
                               JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
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

        // 1. Resolve user ID first without locking
        UUID userId = refreshTokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token."));

        // 2. Consistent lock ordering: Lock User first
        User user = userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token."));

        if (!user.isActive()) {
            throw new InvalidRefreshTokenException("User account is disabled.");
        }

        // 3. Reload and lock refresh token after acquiring user lock
        RefreshToken existingToken = refreshTokenRepository.findByTokenHashWithLock(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token."));

        // Obtain fresh timestamp after acquiring user and token locks
        Instant now = Instant.now();

        if (!existingToken.isValid(now) || !existingToken.getUser().getId().equals(user.getId())) {
            throw new InvalidRefreshTokenException("Invalid refresh token.");
        }

        if (!user.isActive()) {
            throw new InvalidRefreshTokenException("User account is disabled.");
        }

        // 4. Revoke old token and flush
        existingToken.revoke(now);
        refreshTokenRepository.saveAndFlush(existingToken);

        // 5. Issue and persist new refresh token for the same user
        String newRawToken = generateSecureRandomToken();
        String newTokenHash = hashToken(newRawToken);
        Instant newExpiresAt = now.plus(jwtProperties.getRefreshTokenTtl());

        RefreshToken newToken = RefreshToken.create(user, newTokenHash, newExpiresAt);
        refreshTokenRepository.saveAndFlush(newToken);

        return new RotatedToken(newRawToken, user);
    }

    @Transactional
    public void revokeToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }

        String tokenHash = hashToken(rawRefreshToken);

        Optional<UUID> userIdOpt = refreshTokenRepository.findUserIdByTokenHash(tokenHash);
        if (userIdOpt.isPresent()) {
            UUID userId = userIdOpt.get();
            userRepository.findByIdWithLock(userId).ifPresent(u -> {
                Instant now = Instant.now();
                refreshTokenRepository.findByTokenHashWithLock(tokenHash).ifPresent(token -> {
                    if (token.isValid(now)) {
                        token.revoke(now);
                        refreshTokenRepository.saveAndFlush(token);
                    }
                });
                refreshTokenRepository.revokeAllActiveForUserId(userId, now);
            });
        }
    }

    @Transactional
    public void revokeAllActiveForUserId(UUID userId) {
        if (userId != null) {
            refreshTokenRepository.revokeAllActiveForUserId(userId, Instant.now());
        }
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
