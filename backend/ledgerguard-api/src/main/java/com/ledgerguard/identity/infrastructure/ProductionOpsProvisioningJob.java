package com.ledgerguard.identity.infrastructure;

import com.ledgerguard.identity.domain.EmailNormalizer;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Explicit, one-time production CLI task that provisions an initial OPS account
 * from secure deployment environment secrets.
 * <p>
 * Key Invariants:
 * <ul>
 *   <li>Requires dedicated profile: {@code ops-provision}.</li>
 *   <li>Disabled by default; activated strictly when {@code ledgerguard.ops.provision.enabled=true}.</li>
 *   <li>Requires non-web execution with {@code spring.main.web-application-type=none}.</li>
 *   <li>Fails fast if executed within a servlet or reactive web context.</li>
 *   <li>Not an HTTP endpoint; executed exclusively via CLI or one-time container run.</li>
 *   <li>Reads credentials exclusively from deployment properties/secrets.</li>
 *   <li>BCrypt hash only; never logs credentials, tokens, or hashes.</li>
 *   <li>Refuses role promotion if target email already belongs to a CUSTOMER or MERCHANT.</li>
 *   <li>Idempotent for an existing matching active OPS account.</li>
 *   <li>Strictly never provisions a wallet or ledger account (OPS accounts have no wallets).</li>
 *   <li>Exits the process with code 0 upon successful completion; non-zero exit upon failure.</li>
 * </ul>
 */
@Component
@Profile("ops-provision")
@ConditionalOnProperty(prefix = "ledgerguard.ops.provision", name = "enabled", havingValue = "true")
public class ProductionOpsProvisioningJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionOpsProvisioningJob.class);
    private static final int MIN_PASSWORD_CHAR_LENGTH = 12;
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final ProductionOpsProvisioningProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationContext applicationContext;

    public ProductionOpsProvisioningJob(ProductionOpsProvisioningProperties properties,
                                        UserRepository userRepository,
                                        PasswordEncoder passwordEncoder,
                                        ApplicationContext applicationContext) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationContext = applicationContext;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        validateNonWebExecution();
        provisionProductionOpsUser();
        if (properties.isExitOnCompletion()) {
            log.info("[OPS PROVISION] Production OPS provisioning finished. Exiting process with code 0.");
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }

    public void validateNonWebExecution() {
        if (applicationContext instanceof WebApplicationContext
                || applicationContext.getClass().getName().toLowerCase().contains("web")
                || applicationContext.getClass().getName().toLowerCase().contains("reactive")) {
            throw new IllegalStateException("Production OPS provisioning refused: cannot execute in web-server mode. "
                    + "Provisioning must run as a non-web ephemeral CLI task with spring.main.web-application-type=none.");
        }
        String webAppType = applicationContext.getEnvironment().getProperty("spring.main.web-application-type");
        if (webAppType != null && !"none".equalsIgnoreCase(webAppType.trim())) {
            throw new IllegalStateException("Production OPS provisioning refused: spring.main.web-application-type must be 'none'. "
                    + "Current value: " + webAppType);
        }
    }

    public void provisionProductionOpsUser() {
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
                String passwordHash = passwordEncoder.encode(rawPassword);
                existing.updatePassword(passwordHash);
                existing.updateFullName(normalizedFullName);
                userRepository.saveAndFlush(existing);
                log.info("[OPS PROVISION] Successfully rotated password for existing OPS user: email={}", normalizedEmail);
                return;
            }

            log.info("[OPS PROVISION] Matching active production OPS account already exists for {}. Idempotent no-op completed.", normalizedEmail);
            return;
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
    }
}
