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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Development-only, explicitly enabled application startup runner that provisions
 * an OPS account for local development and portfolio demonstration.
 * <p>
 * Security and Invariant Contracts:
 * <ul>
 *   <li>Only active under the strict {@code dev} profile ({@code @Profile("dev")}).</li>
 *   <li>Requires explicit enabled property ({@code ledgerguard.bootstrap.ops.enabled=true}).</li>
 *   <li>Refuses execution if a production profile is detected.</li>
 *   <li>Fails fast with sanitized exceptions when enabled but configuration is invalid.</li>
 *   <li>Applies the authoritative password policy (minimum 12 characters, max 72 UTF-8 bytes).</li>
 *   <li>Hashes credentials using BCrypt via the shared {@link PasswordEncoder}; never stores or logs plaintext passwords.</li>
 *   <li>Provisions only the {@link User} entity; strictly NEVER provisions a wallet or ledger account.</li>
 *   <li>Idempotent on repeated startup if an ACTIVE OPS account already exists (preserves existing credentials).</li>
 *   <li>Fails fast if the configured email belongs to a CUSTOMER or MERCHANT (no promotion).</li>
 *   <li>Fails fast if the configured OPS account is DISABLED (no silent reactivation).</li>
 *   <li>Handles concurrent startup races safely using isolated transactions without transaction state corruption.</li>
 * </ul>
 */
@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "ledgerguard.bootstrap.ops", name = "enabled", havingValue = "true")
public class DevelopmentOpsBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentOpsBootstrap.class);
    private static final int MIN_PASSWORD_CHAR_LENGTH = 12;
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final OpsBootstrapProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public DevelopmentOpsBootstrap(OpsBootstrapProperties properties,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   Environment environment,
                                   PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        if (transactionManager != null) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.transactionTemplate = template;
        } else {
            this.transactionTemplate = null;
        }
    }

    public DevelopmentOpsBootstrap(OpsBootstrapProperties properties,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   Environment environment) {
        this(properties, userRepository, passwordEncoder, environment, null);
    }

    @Override
    public void run(ApplicationArguments args) {
        verifyEnvironmentSafety();
        provisionOpsUser();
    }

    private void verifyEnvironmentSafety() {
        if (environment != null) {
            for (String profile : environment.getActiveProfiles()) {
                if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                    throw new IllegalStateException("DevelopmentOpsBootstrap is strictly forbidden in production profile.");
                }
            }
        }
    }

    public void provisionOpsUser() {
        if (!properties.isEnabled()) {
            return;
        }

        String rawEmail = properties.getEmail();
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalStateException("OPS bootstrap failed: email must not be blank when OPS bootstrap is enabled.");
        }
        String normalizedEmail = EmailNormalizer.normalize(rawEmail);
        if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new IllegalStateException("OPS bootstrap failed: invalid email address '" + normalizedEmail + "'.");
        }

        String rawPassword = properties.getPassword();
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalStateException("OPS bootstrap failed: password must not be blank when OPS bootstrap is enabled.");
        }
        if (rawPassword.length() < MIN_PASSWORD_CHAR_LENGTH) {
            throw new IllegalStateException("OPS bootstrap failed: password must be at least " + MIN_PASSWORD_CHAR_LENGTH + " characters.");
        }
        byte[] utf8Bytes = rawPassword.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > MAX_PASSWORD_UTF8_BYTES) {
            throw new IllegalStateException("OPS bootstrap failed: password exceeds maximum allowed BCrypt byte length of " + MAX_PASSWORD_UTF8_BYTES + " UTF-8 bytes.");
        }

        String rawFullName = properties.getFullName();
        if (rawFullName != null && rawFullName.isBlank()) {
            throw new IllegalStateException("OPS bootstrap failed: full name must not be blank when configured.");
        }
        String normalizedFullName = (rawFullName == null) ? "Operations Engineer" : rawFullName.trim();
        if (normalizedFullName.length() < 2 || normalizedFullName.length() > 120) {
            throw new IllegalStateException("OPS bootstrap failed: full name must be between 2 and 120 characters.");
        }

        // Step 1: In an isolated transaction, verify whether the user already exists.
        User existing = executeInTransaction(status ->
                userRepository.findByEmail(normalizedEmail).orElse(null)
        );

        if (existing != null) {
            if (existing.getRole() == UserRole.OPS && existing.getStatus() == UserStatus.ACTIVE) {
                log.info("OPS bootstrap: active OPS account already exists for {}. Startup is idempotent; credentials not modified.", normalizedEmail);
                return;
            }
            if (existing.getRole() != UserRole.OPS) {
                throw new IllegalStateException("OPS bootstrap failed: email '" + normalizedEmail + "' is already registered to a "
                        + existing.getRole() + " account. Promotion to OPS is strictly forbidden.");
            }
            throw new IllegalStateException("OPS bootstrap failed: OPS account for email '" + normalizedEmail
                    + "' exists but is " + existing.getStatus() + ". Silent reactivation is strictly forbidden.");
        }

        // Step 2: Attempt to persist the new OPS user in an isolated transaction.
        String passwordHash = passwordEncoder.encode(rawPassword);
        User opsUser = User.create(normalizedFullName, normalizedEmail, passwordHash, UserRole.OPS);

        boolean inserted = false;
        try {
            executeInTransaction(status -> {
                userRepository.saveAndFlush(opsUser);
                return null;
            });
            inserted = true;
            log.info("OPS bootstrap: successfully provisioned active OPS account for email: {}", normalizedEmail);
        } catch (DataIntegrityViolationException ex) {
            log.info("OPS bootstrap: concurrent creation detected for email: {}. Resolving winning record.", normalizedEmail);
        }

        // Step 3: If insert encountered a concurrent constraint conflict, evaluate the winning record in a fresh transaction.
        if (!inserted) {
            User winner = executeInTransaction(status ->
                    userRepository.findByEmail(normalizedEmail).orElse(null)
            );
            if (winner != null) {
                if (winner.getRole() == UserRole.OPS && winner.getStatus() == UserStatus.ACTIVE) {
                    log.info("OPS bootstrap: concurrent creation resolved idempotently for {}.", normalizedEmail);
                    return;
                }
                if (winner.getRole() != UserRole.OPS) {
                    throw new IllegalStateException("OPS bootstrap failed: concurrent account with email '" + normalizedEmail
                            + "' was created with non-OPS role " + winner.getRole() + ". Role promotion is strictly forbidden.");
                }
                throw new IllegalStateException("OPS bootstrap failed: concurrent OPS account with email '" + normalizedEmail
                        + "' was created with status " + winner.getStatus() + ". Silent reactivation is strictly forbidden.");
            }
            throw new IllegalStateException("OPS bootstrap failed: unique constraint violation for email '" + normalizedEmail + "' but user record could not be resolved.");
        }
    }

    private <T> T executeInTransaction(TransactionCallback<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(action);
        }
        return action.doInTransaction(new SimpleTransactionStatus());
    }
}
