package com.ledgerguard.identity.application;

import com.ledgerguard.identity.api.dto.AuthResponse;
import com.ledgerguard.identity.api.dto.LoginRequest;
import com.ledgerguard.identity.api.dto.RegisterRequest;
import com.ledgerguard.identity.api.dto.UserSummaryResponse;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.shared.security.JwtProperties;
import com.ledgerguard.shared.security.JwtTokenService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final int MIN_PASSWORD_CHAR_LENGTH = 12;
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       RefreshTokenService refreshTokenService,
                       JwtTokenService jwtTokenService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.jwtTokenService = jwtTokenService;
        this.jwtProperties = jwtProperties;
    }

    public record AuthResult(AuthResponse authResponse, String rawRefreshToken) {}

    @Transactional
    public UserSummaryResponse register(RegisterRequest request) {
        if (request.role() == UserRole.OPS) {
            throw new ForbiddenRegistrationException("Registration with OPS role is not permitted.");
        }

        validatePasswordPolicy(request.password());

        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException("Email is already registered.");
        }

        UserRole role = request.role() != null ? request.role() : UserRole.CUSTOMER;
        // Password is not trimmed or lowercased
        String encodedPassword = passwordEncoder.encode(request.password());

        User user = User.create(normalizedEmail, encodedPassword, role);
        try {
            User savedUser = userRepository.saveAndFlush(user);
            return UserSummaryResponse.from(savedUser);
        } catch (DataIntegrityViolationException e) {
            // Protect against concurrent duplicate registration race condition
            throw new EmailAlreadyRegisteredException("Email is already registered.");
        }
    }

    @Transactional
    public AuthResult login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash()) || !user.isActive()) {
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        String accessToken = jwtTokenService.generateAccessToken(user);
        RefreshTokenService.GeneratedToken generatedToken = refreshTokenService.createRefreshToken(user);

        long expiresIn = jwtProperties.getAccessTokenTtl().toSeconds();
        AuthResponse authResponse = AuthResponse.of(accessToken, expiresIn, UserSummaryResponse.from(user));

        return new AuthResult(authResponse, generatedToken.rawToken());
    }

    @Transactional
    public AuthResult refresh(String rawRefreshToken) {
        RefreshTokenService.RotatedToken rotated = refreshTokenService.rotateRefreshToken(rawRefreshToken);
        User user = rotated.user();

        // Enforce that disabled accounts cannot extend authentication via refresh
        if (!user.isActive()) {
            refreshTokenService.revokeToken(rotated.rawToken());
            throw new InvalidRefreshTokenException("User account is disabled.");
        }

        String newAccessToken = jwtTokenService.generateAccessToken(user);
        long expiresIn = jwtProperties.getAccessTokenTtl().toSeconds();
        AuthResponse authResponse = AuthResponse.of(newAccessToken, expiresIn, UserSummaryResponse.from(user));

        return new AuthResult(authResponse, rotated.rawToken());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revokeToken(rawRefreshToken);
        }
    }

    @Transactional(readOnly = true)
    public UserSummaryResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found."));
        return UserSummaryResponse.from(user);
    }

    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < MIN_PASSWORD_CHAR_LENGTH) {
            throw new InvalidPasswordException("Password must be at least " + MIN_PASSWORD_CHAR_LENGTH + " characters.");
        }
        byte[] utf8Bytes = password.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > MAX_PASSWORD_UTF8_BYTES) {
            throw new InvalidPasswordException("Password exceeds maximum allowed BCrypt byte length of " + MAX_PASSWORD_UTF8_BYTES + " UTF-8 bytes.");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
