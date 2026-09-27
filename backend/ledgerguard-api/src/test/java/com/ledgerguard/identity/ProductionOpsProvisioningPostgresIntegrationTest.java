package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.identity.infrastructure.ProductionOpsProvisioningJob;
import com.ledgerguard.identity.infrastructure.ProductionOpsProvisioningProperties;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "ops-provision"})
@DisplayName("ProductionOpsProvisioning PostgreSQL Integration Test")
class ProductionOpsProvisioningPostgresIntegrationTest extends AbstractIntegrationTest {

    private static final String PROVISIONED_EMAIL = "ops.prod.test@ledgerguard.example.com";
    private static final String PROVISIONED_PASSWORD = "ValidProductionPassword123!";

    @DynamicPropertySource
    static void configureProductionOpsProvisioning(DynamicPropertyRegistry registry) {
        registry.add("ledgerguard.ops.provision.enabled", () -> "true");
        registry.add("ledgerguard.ops.provision.email", () -> PROVISIONED_EMAIL);
        registry.add("ledgerguard.ops.provision.password", () -> PROVISIONED_PASSWORD);
        registry.add("ledgerguard.ops.provision.full-name", () -> "Production Ops Officer");
        registry.add("ledgerguard.ops.provision.exit-on-completion", () -> "false");
        registry.add("spring.main.web-application-type", () -> "none");
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProductionOpsProvisioningJob job;

    @Autowired
    private ProductionOpsProvisioningProperties properties;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Provisions new OPS identity into PostgreSQL with BCrypt hash and no plaintext password")
    void provisionsNewOpsIdentityInPostgres() {
        Optional<User> opsUserOpt = userRepository.findByEmail(PROVISIONED_EMAIL);
        assertThat(opsUserOpt).isPresent();

        User opsUser = opsUserOpt.get();
        assertThat(opsUser.getEmail()).isEqualTo(PROVISIONED_EMAIL);
        assertThat(opsUser.getRole()).isEqualTo(UserRole.OPS);
        assertThat(opsUser.getStatus()).isEqualTo(UserStatus.ACTIVE);

        // Prove BCrypt hash only: starts with $2a$ and never matches plaintext string directly
        assertThat(opsUser.getPasswordHash()).startsWith("$2a$");
        assertThat(opsUser.getPasswordHash()).isNotEqualTo(PROVISIONED_PASSWORD);
        assertThat(passwordEncoder.matches(PROVISIONED_PASSWORD, opsUser.getPasswordHash())).isTrue();

        // Verify database raw query confirms plaintext is not stored in any column
        String rawPasswordHashInDb = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String.class,
                opsUser.getId()
        );
        assertThat(rawPasswordHashInDb).isEqualTo(opsUser.getPasswordHash());
        assertThat(rawPasswordHashInDb).doesNotContain(PROVISIONED_PASSWORD);
    }

    @Test
    @DisplayName("Strictly never creates a wallet or ledger account for OPS identity")
    void noWalletOrLedgerAccountCreated() {
        Optional<User> opsUserOpt = userRepository.findByEmail(PROVISIONED_EMAIL);
        assertThat(opsUserOpt).isPresent();
        UUID opsUserId = opsUserOpt.get().getId();

        // Ledger accounts check
        Integer ledgerAccountCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_accounts WHERE owner_user_id = ?",
                Integer.class,
                opsUserId
        );
        assertThat(ledgerAccountCount).isZero();

        // Balance snapshots check
        Integer snapshotCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_balance_snapshots WHERE ledger_account_id IN (SELECT id FROM ledger_accounts WHERE owner_user_id = ?)",
                Integer.class,
                opsUserId
        );
        assertThat(snapshotCount).isZero();
    }

    @Test
    @DisplayName("Idempotent when active OPS user already exists in PostgreSQL")
    void existingActiveOpsProducesIdempotentNoOp() {
        Optional<User> initialUser = userRepository.findByEmail(PROVISIONED_EMAIL);
        assertThat(initialUser).isPresent();
        UUID initialId = initialUser.get().getId();

        // Second execution should complete cleanly as an idempotent no-op
        job.provisionProductionOpsUser();

        Optional<User> afterUser = userRepository.findByEmail(PROVISIONED_EMAIL);
        assertThat(afterUser).isPresent();
        assertThat(afterUser.get().getId()).isEqualTo(initialId);

        Integer totalMatching = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?",
                Integer.class,
                PROVISIONED_EMAIL
        );
        assertThat(totalMatching).isEqualTo(1);
    }

    @Test
    @DisplayName("Refuses promotion of existing CUSTOMER or MERCHANT to OPS role")
    void refusesPromotionOfExistingCustomerOrMerchant() {
        // Create an existing CUSTOMER
        String customerEmail = "customer.existing@ledgerguard.example.com";
        User existingCustomer = new User(
                UUID.randomUUID(),
                customerEmail,
                passwordEncoder.encode("CustomerPass123!"),
                UserRole.CUSTOMER,
                UserStatus.ACTIVE
        );
        userRepository.saveAndFlush(existingCustomer);

        // Attempting to provision OPS on that email must fail fast
        properties.setEmail(customerEmail);
        try {
            assertThatThrownBy(() -> job.provisionProductionOpsUser())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot promote existing non-OPS user to OPS role");

            // Verify customer still retains CUSTOMER role in DB
            User unchanged = userRepository.findByEmail(customerEmail).orElseThrow();
            assertThat(unchanged.getRole()).isEqualTo(UserRole.CUSTOMER);
        } finally {
            // Restore valid email
            properties.setEmail(PROVISIONED_EMAIL);
        }

        // Create an existing MERCHANT
        String merchantEmail = "merchant.existing@ledgerguard.example.com";
        User existingMerchant = new User(
                UUID.randomUUID(),
                merchantEmail,
                passwordEncoder.encode("MerchantPass123!"),
                UserRole.MERCHANT,
                UserStatus.ACTIVE
        );
        userRepository.saveAndFlush(existingMerchant);

        // Attempting to provision OPS on merchant email must fail fast
        properties.setEmail(merchantEmail);
        try {
            assertThatThrownBy(() -> job.provisionProductionOpsUser())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot promote existing non-OPS user to OPS role");

            User unchanged = userRepository.findByEmail(merchantEmail).orElseThrow();
            assertThat(unchanged.getRole()).isEqualTo(UserRole.MERCHANT);
        } finally {
            // Restore valid email
            properties.setEmail(PROVISIONED_EMAIL);
        }
    }

    @Test
    @DisplayName("Rejects blank, short, and over-72-byte passwords")
    void rejectsInvalidPasswords() {
        String originalPassword = properties.getPassword();
        try {
            // Blank password
            properties.setPassword("   ");
            assertThatThrownBy(() -> job.provisionProductionOpsUser())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("password must not be blank");

            // Short password (< 12 chars)
            properties.setPassword("Short123");
            assertThatThrownBy(() -> job.provisionProductionOpsUser())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("password must be at least 12 characters");

            // Over-72-byte password
            properties.setPassword("a".repeat(73));
            assertThatThrownBy(() -> job.provisionProductionOpsUser())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("password exceeds maximum 72 UTF-8 bytes");
        } finally {
            properties.setPassword(originalPassword);
        }
    }

    @Test
    @DisplayName("Process completion behavior is verified: exitOnCompletion is configurable")
    void processCompletionDocumented() {
        assertThat(properties.isExitOnCompletion()).isFalse();
        // Running with exit-on-completion=false completes idempotently without halting JVM
        job.run(new org.springframework.boot.DefaultApplicationArguments());
        Optional<User> opsUserOpt = userRepository.findByEmail(PROVISIONED_EMAIL);
        assertThat(opsUserOpt).isPresent();
    }

    @Test
    @DisplayName("Rotates password in PostgreSQL only when explicitly requested")
    void rotatesPasswordOnlyWhenExplicitlyRequested() {
        Optional<User> opsUserOpt = userRepository.findByEmail(PROVISIONED_EMAIL);
        assertThat(opsUserOpt).isPresent();
        String initialHash = opsUserOpt.get().getPasswordHash();

        String newPassword = "NewRotatedPassword789!";
        properties.setPassword(newPassword);

        try {
            // When rotatePassword is false, provisioning is an idempotent no-op
            properties.setRotatePassword(false);
            job.provisionProductionOpsUser();

            User unchangedUser = userRepository.findByEmail(PROVISIONED_EMAIL).orElseThrow();
            assertThat(unchangedUser.getPasswordHash()).isEqualTo(initialHash);
            assertThat(passwordEncoder.matches(newPassword, unchangedUser.getPasswordHash())).isFalse();

            // When rotatePassword is true, provisioning updates password hash in PostgreSQL
            properties.setRotatePassword(true);
            properties.setFullName("Updated Ops Officer");
            job.provisionProductionOpsUser();

            User updatedUser = userRepository.findByEmail(PROVISIONED_EMAIL).orElseThrow();
            assertThat(updatedUser.getPasswordHash()).isNotEqualTo(initialHash);
            assertThat(passwordEncoder.matches(newPassword, updatedUser.getPasswordHash())).isTrue();
            assertThat(updatedUser.getFullName()).isEqualTo("Updated Ops Officer");
        } finally {
            // Restore original password and settings
            properties.setRotatePassword(false);
            properties.setPassword(PROVISIONED_PASSWORD);
            properties.setFullName("Production Ops Officer");
            properties.setRotatePassword(true);
            job.provisionProductionOpsUser();
            properties.setRotatePassword(false);
        }
    }

    @Test
    @DisplayName("Rejects blank or invalid full names")
    void rejectsInvalidFullName() {
        String originalFullName = properties.getFullName();
        try {
            properties.setFullName("  ");
            assertThatThrownBy(() -> job.provisionProductionOpsUser())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("full name must not be blank");

            properties.setFullName("X");
            assertThatThrownBy(() -> job.provisionProductionOpsUser())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("full name must be between 2 and 120 characters");
        } finally {
            properties.setFullName(originalFullName);
        }
    }

    @Test
    @DisplayName("Provisioning cannot run in web-server mode")
    void provisioningCannotRunInWebServerMode() {
        WebApplicationContext mockWebContext = mock(WebApplicationContext.class);
        Environment mockEnv = mock(Environment.class);
        when(mockWebContext.getEnvironment()).thenReturn(mockEnv);

        ProductionOpsProvisioningJob webJob = new ProductionOpsProvisioningJob(
                properties, userRepository, passwordEncoder, mockWebContext
        );

        assertThatThrownBy(webJob::validateNonWebExecution)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot execute in web-server mode");
    }
}
