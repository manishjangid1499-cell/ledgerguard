package com.ledgerguard.identity.infrastructure;

import com.ledgerguard.identity.domain.EmailNormalizer;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Transactional service executing database persistence and credential updates
 * for production OPS account provisioning.
 * <p>
 * Guaranteed to commit its transaction before returning control to the caller.
 */
@Service
public class ProductionOpsProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProductionOpsProvisioningService.class);
    private static final int MIN_PASSWORD_CHAR_LENGTH = 12;
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public enum ProvisioningOutcome {
        CREATED,
        ALREADY_EXISTS,
        PASSWORD_ROTATED
    }

    public record ProvisioningResult(
            ProvisioningOutcome outcome,
            UUID userId,
            String email
    ) {}

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ledgerguard.identity.application.RefreshTokenService refreshTokenService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ledgerguard.identity.domain.PasswordResetTokenRepository passwordResetTokenRepository;

    public ProductionOpsProvisioningService(UserRepository userRepository,
                                            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ProvisioningResult provision(ProductionOpsProvisioningProperties properties) {
        String rawEmail = properties.getEmail();
        if (rawEmail == null || rawEmail.trim().isEmpty()) {
            throw new IllegalStateException("Production OPS provisioning failed: email must not be blank.");
        }
        String normalizedEmail = EmailNormalizer.normalize(rawEmail);
        if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new IllegalStateException("Production OPS provisioning failed: email format is invalid.");
        }

        String rawFullName = properties.getFullName();
        if (rawFullName == null || rawFullName.trim().isEmpty()) {
            throw new IllegalStateException("Production OPS provisioning failed: full name must not be blank.");
        }
        String normalizedFullName = rawFullName.trim();
        if (normalizedFullName.length() < 2 || normalizedFullName.length() > 120) {
            throw new IllegalStateException("Production OPS provisioning failed: full name must be between 2 and 120 characters.");
        }

        String rawPassword = properties.getPassword();
        if (rawPassword == null || rawPassword.trim().isEmpty()) {
            throw new IllegalStateException("Production OPS provisioning failed: password must not be blank.");
        }
        if (rawPassword.length() < MIN_PASSWORD_CHAR_LENGTH) {
            throw new IllegalStateException("Production OPS provisioning failed: password must be at least "
                    + MIN_PASSWORD_CHAR_LENGTH + " characters.");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_UTF8_BYTES) {
            throw new IllegalStateException("Production OPS provisioning failed: password exceeds maximum "
                    + MAX_PASSWORD_UTF8_BYTES + " UTF-8 bytes limit.");
        }

        Optional<User> existingUserOpt = userRepository.findByEmail(normalizedEmail);
        if (existingUserOpt.isPresent()) {
            User existing = existingUserOpt.get();
            if (existing.getRole() != UserRole.OPS) {
                log.error("[SECURITY ALERT] Production OPS provisioning refused: cannot promote existing non-OPS user ({}) with role {} to OPS.",
                        normalizedEmail, existing.getRole());
                throw new IllegalStateException("Production OPS provisioning refused: cannot promote existing non-OPS user to OPS role: "
                        + normalizedEmail);
            }
            if (existing.getStatus() == UserStatus.DISABLED) {
                log.error("[SECURITY ALERT] Production OPS provisioning refused: existing OPS account ({}) is DISABLED.", normalizedEmail);
                throw new IllegalStateException("Production OPS provisioning refused: existing OPS account is DISABLED: " + normalizedEmail);
            }

            if (properties.isRotatePassword()) {
                if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                    existing = userRepository.findByIdWithLock(existing.getId()).orElse(existing);
                }
                String passwordHash = passwordEncoder.encode(rawPassword);
                existing.updatePassword(passwordHash);
                existing.updateFullName(normalizedFullName);
                userRepository.saveAndFlush(existing);
                if (refreshTokenService != null) {
                    refreshTokenService.revokeAllActiveForUserId(existing.getId());
                }
                if (passwordResetTokenRepository != null) {
                    passwordResetTokenRepository.invalidateAllActiveForUserId(existing.getId(), java.time.Instant.now());
                }
                log.info("[OPS PROVISION] Successfully rotated password for existing OPS user: email={}", normalizedEmail);
                return new ProvisioningResult(ProvisioningOutcome.PASSWORD_ROTATED, existing.getId(), normalizedEmail);
            }

            log.info("[OPS PROVISION] Matching active production OPS account already exists for {}. Idempotent no-op completed.", normalizedEmail);
            return new ProvisioningResult(ProvisioningOutcome.ALREADY_EXISTS, existing.getId(), normalizedEmail);
        }

        String passwordHash = passwordEncoder.encode(rawPassword);
        User newOpsUser = new User(
                UUID.randomUUID(),
                normalizedFullName,
                normalizedEmail,
                passwordHash,
                UserRole.OPS,
                UserStatus.ACTIVE
        );

        userRepository.saveAndFlush(newOpsUser);
        log.info("[OPS PROVISION] Successfully provisioned production OPS user: id={}, email={}",
                newOpsUser.getId(), normalizedEmail);

        return new ProvisioningResult(ProvisioningOutcome.CREATED, newOpsUser.getId(), normalizedEmail);
    }
}
