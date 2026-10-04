package com.ledgerguard.identity.application;

import com.ledgerguard.identity.api.dto.ChangePasswordRequest;
import com.ledgerguard.identity.api.dto.ForgotPasswordRequest;
import com.ledgerguard.identity.api.dto.GenericMessageResponse;
import com.ledgerguard.identity.api.dto.ResetPasswordRequest;
import com.ledgerguard.identity.domain.EmailNormalizer;
import com.ledgerguard.identity.domain.PasswordResetToken;
import com.ledgerguard.identity.domain.PasswordResetTokenRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.identity.infrastructure.PasswordRecoveryEmailDispatcher;
import com.ledgerguard.identity.infrastructure.PasswordRecoveryProperties;
import com.ledgerguard.identity.infrastructure.PasswordRecoveryThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    public static final String GENERIC_FORGOT_PASSWORD_MESSAGE =
            "If an eligible account exists, you will receive a password reset link.";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordRecoveryProperties properties;
    private final PasswordRecoveryThrottle throttle;
    private final PasswordRecoveryEmailDispatcher emailDispatcher;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordRecoveryService(UserRepository userRepository,
                                   PasswordResetTokenRepository passwordResetTokenRepository,
                                   RefreshTokenService refreshTokenService,
                                   PasswordEncoder passwordEncoder,
                                   PasswordRecoveryProperties properties,
                                   PasswordRecoveryThrottle throttle,
                                   PasswordRecoveryEmailDispatcher emailDispatcher) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.throttle = throttle;
        this.emailDispatcher = emailDispatcher;
    }

    @Transactional
    public GenericMessageResponse forgotPassword(ForgotPasswordRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.email());

        // Check throttle: if within cooldown, do not generate new token or send email,
        // but return identical generic response
        if (!throttle.tryAcquire(normalizedEmail)) {
            log.info("Password reset request for {} throttled by cooldown", normalizedEmail);
            return new GenericMessageResponse(GENERIC_FORGOT_PASSWORD_MESSAGE);
        }

        // Lock user if exists
        Optional<User> userOpt = userRepository.findByEmailWithLock(normalizedEmail);
        if (userOpt.isEmpty()) {
            return new GenericMessageResponse(GENERIC_FORGOT_PASSWORD_MESSAGE);
        }

        User user = userOpt.get();
        // Only ACTIVE CUSTOMER and MERCHANT accounts are eligible for public recovery
        if (user.getStatus() != UserStatus.ACTIVE || (user.getRole() != UserRole.CUSTOMER && user.getRole() != UserRole.MERCHANT)) {
            log.info("Password reset requested for ineligible account (role={}, status={})", user.getRole(), user.getStatus());
            return new GenericMessageResponse(GENERIC_FORGOT_PASSWORD_MESSAGE);
        }

        // Clean up expired or already consumed tokens lazily
        passwordResetTokenRepository.deleteExpiredOrConsumed(Instant.now());

        // Generate 256-bit token
        String rawToken = generateSecureRandomToken();
        String tokenHash = hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTokenTtl());

        PasswordResetToken resetToken = PasswordResetToken.create(user, tokenHash, expiresAt);
        passwordResetTokenRepository.save(resetToken);

        // Schedule email dispatch AFTER transaction commits
        String resetUrl = properties.getFrontendBaseUrl() + "/reset-password?token=" + rawToken;
        String subject = "Reset your LedgerGuard password";
        String body = "Hello " + (user.getFullName() != null ? user.getFullName() : "LedgerGuard User") + ",\n\n"
                + "A password reset request was received for your LedgerGuard account.\n\n"
                + "To set a new password, visit the link below (valid for " + properties.getTokenTtl().toMinutes() + " minutes):\n"
                + resetUrl + "\n\n"
                + "If you did not request this, please ignore this email.\n";

        String recipientEmail = user.getEmail();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailDispatcher.enqueueEmail(recipientEmail, subject, body);
                }
            });
        } else {
            emailDispatcher.enqueueEmail(recipientEmail, subject, body);
        }

        return new GenericMessageResponse(GENERIC_FORGOT_PASSWORD_MESSAGE);
    }

    @Transactional
    public GenericMessageResponse resetPassword(ResetPasswordRequest request) {
        String rawToken = request.token();
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidPasswordResetTokenException("Invalid, expired, or already used reset token.");
        }

        PasswordPolicyValidator.validate(request.newPassword());

        String tokenHash = hashToken(rawToken);

        // 1. Resolve user ID without treating initial token read as authoritative
        UUID userId = passwordResetTokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidPasswordResetTokenException("Invalid, expired, or already used reset token."));

        // 2. Consistent lock ordering: Always lock User first
        User user = userRepository.findByIdWithLock(userId)
                .orElseThrow(() -> new InvalidPasswordResetTokenException("Account not found."));

        // 3. Reload and lock the token from current database state after acquiring user lock
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHashWithLock(tokenHash)
                .orElseThrow(() -> new InvalidPasswordResetTokenException("Invalid, expired, or already used reset token."));

        Instant now = Instant.now();

        // 4. Recheck consumed status, expiration (expiresAt <= now), and token user ownership
        if (token.isConsumed() || token.isExpired(now) || !token.getUser().getId().equals(user.getId())) {
            throw new InvalidPasswordResetTokenException("Invalid, expired, or already used reset token.");
        }

        // 5. Recheck account status and role eligibility upon consumption
        if (user.getStatus() != UserStatus.ACTIVE || (user.getRole() != UserRole.CUSTOMER && user.getRole() != UserRole.MERCHANT)) {
            throw new InvalidPasswordResetTokenException("Account is not eligible for password reset.");
        }

        // 6. Reject new password matching existing password
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new InvalidPasswordException("New password cannot be the same as the current password.");
        }

        // 7. Update password, increment credential version, and flush before any bulk updates
        String encodedNewPassword = passwordEncoder.encode(request.newPassword());
        user.updatePassword(encodedNewPassword);
        userRepository.saveAndFlush(user);

        // 8. Consume token and flush before bulk updates
        token.consume(now);
        passwordResetTokenRepository.saveAndFlush(token);

        // 9. Invalidate all other reset tokens and revoke all refresh tokens
        passwordResetTokenRepository.invalidateAllActiveForUserId(user.getId(), now);
        refreshTokenService.revokeAllActiveForUserId(user.getId());

        // Send confirmation email after commit
        String subject = "Your LedgerGuard password was updated";
        String body = "Hello " + (user.getFullName() != null ? user.getFullName() : "LedgerGuard User") + ",\n\n"
                + "Your password has been successfully reset. If you did not make this change, please contact security immediately.\n";
        String recipientEmail = user.getEmail();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailDispatcher.enqueueEmail(recipientEmail, subject, body);
                }
            });
        } else {
            emailDispatcher.enqueueEmail(recipientEmail, subject, body);
        }

        return new GenericMessageResponse("Password has been reset successfully. Please log in with your new password.");
    }

    @Transactional
    public GenericMessageResponse changePassword(UUID authenticatedUserId, ChangePasswordRequest request) {
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId must not be null");
        PasswordPolicyValidator.validate(request.newPassword());

        // Lock user first
        User user = userRepository.findByIdWithLock(authenticatedUserId)
                .orElseThrow(() -> new InvalidCredentialsException("User not found."));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException("User account is not active.");
        }

        // Verify current password
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect.");
        }

        // Reject existing password as new password
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new InvalidPasswordException("New password cannot be the same as the current password.");
        }

        // Update password and increment credential version
        String encodedNewPassword = passwordEncoder.encode(request.newPassword());
        user.updatePassword(encodedNewPassword);
        userRepository.saveAndFlush(user);
        log.info("[PASSWORD CHANGE] Password changed for userId={}, incremented cv to {}", user.getId(), user.getCredentialVersion());

        // Invalidate all outstanding reset tokens
        passwordResetTokenRepository.invalidateAllActiveForUserId(user.getId(), Instant.now());

        // Revoke all refresh tokens
        refreshTokenService.revokeAllActiveForUserId(user.getId());

        // Send confirmation email after commit
        String subject = "Your LedgerGuard password was changed";
        String body = "Hello " + (user.getFullName() != null ? user.getFullName() : "LedgerGuard User") + ",\n\n"
                + "Your password has been successfully updated. If you did not make this change, please contact security immediately.\n";
        String recipientEmail = user.getEmail();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emailDispatcher.enqueueEmail(recipientEmail, subject, body);
                }
            });
        } else {
            emailDispatcher.enqueueEmail(recipientEmail, subject, body);
        }

        return new GenericMessageResponse("Password has been changed successfully. Please log in again.");
    }

    private String generateSecureRandomToken() {
        byte[] bytes = new byte[32]; // 256 bits of entropy
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
}
