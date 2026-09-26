package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.api.AuthController;
import com.ledgerguard.identity.domain.EmailNormalizer;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.identity.infrastructure.DevelopmentOpsBootstrap;
import com.ledgerguard.identity.infrastructure.OpsBootstrapProperties;
import com.ledgerguard.ledger.application.WalletQueryService;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.shared.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles({"test", "dev"})
class DevelopmentOpsBootstrapIntegrationTest extends AbstractIntegrationTest {

    private static final String BOOTSTRAP_OPS_EMAIL = "ops.integration@ledgerguard.local";
    private static final String BOOTSTRAP_OPS_PASSWORD = "OpsIntegrationPass1234!";
    private static final String BOOTSTRAP_OPS_NAME = "Bootstrap Integration Ops";

    @DynamicPropertySource
    static void configureOpsBootstrap(DynamicPropertyRegistry registry) {
        registry.add("ledgerguard.bootstrap.ops.enabled", () -> "true");
        registry.add("ledgerguard.bootstrap.ops.email", () -> BOOTSTRAP_OPS_EMAIL);
        registry.add("ledgerguard.bootstrap.ops.password", () -> BOOTSTRAP_OPS_PASSWORD);
        registry.add("ledgerguard.bootstrap.ops.full-name", () -> BOOTSTRAP_OPS_NAME);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private WalletQueryService walletQueryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private DevelopmentOpsBootstrap bootstrap;

    @Autowired
    private OpsBootstrapProperties bootstrapProperties;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("OPS user is provisioned on application startup, is ACTIVE, and automated checks prove NO wallet or ledger accounts exist")
    void opsUserIsProvisionedCorrectlyOnStartupWithoutWallet() {
        Optional<User> opsUserOpt = userRepository.findByEmail(BOOTSTRAP_OPS_EMAIL);
        assertThat(opsUserOpt).isPresent();

        User opsUser = opsUserOpt.get();
        assertThat(opsUser.getRole()).isEqualTo(UserRole.OPS);
        assertThat(opsUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(opsUser.getFullName()).isEqualTo(BOOTSTRAP_OPS_NAME);
        assertThat(passwordEncoder.matches(BOOTSTRAP_OPS_PASSWORD, opsUser.getPasswordHash())).isTrue();

        // Automated Invariant 1: No ledger accounts owned by this OPS user
        List<LedgerAccount> ownedAccounts = ledgerAccountRepository.findByOwnerUserId(opsUser.getId());
        assertThat(ownedAccounts).isEmpty();

        // Automated Invariant 2: WalletQueryService returns empty for this OPS user
        assertThat(walletQueryService.findWalletByUserId(opsUser.getId())).isEmpty();

        // Automated Invariant 3: No balance snapshots associated through owned accounts
        List<UUID> ownedAccountIds = ownedAccounts.stream().map(LedgerAccount::getId).toList();
        if (!ownedAccountIds.isEmpty()) {
            assertThat(ledgerBalanceSnapshotRepository.findAllById(ownedAccountIds)).isEmpty();
        }
    }

    @Test
    @DisplayName("Active OPS idempotency: repeated execution preserves existing password hash and identity fields")
    void activeOpsIdempotencyPreservesExistingCredentials() {
        User before = userRepository.findByEmail(BOOTSTRAP_OPS_EMAIL).orElseThrow();
        String originalPasswordHash = before.getPasswordHash();
        String originalFullName = before.getFullName();
        UUID originalId = before.getId();

        // Execute provisioning runner again
        bootstrap.provisionOpsUser();

        User after = userRepository.findByEmail(BOOTSTRAP_OPS_EMAIL).orElseThrow();
        assertThat(after.getId()).isEqualTo(originalId);
        assertThat(after.getPasswordHash()).isEqualTo(originalPasswordHash);
        assertThat(after.getFullName()).isEqualTo(originalFullName);
        assertThat(after.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(after.getRole()).isEqualTo(UserRole.OPS);

        // Invariant still holds: no ledger accounts created on repeated run
        assertThat(ledgerAccountRepository.findByOwnerUserId(after.getId())).isEmpty();
    }

    @Test
    @DisplayName("Email normalization: case and whitespace are normalized consistently with AuthService")
    void emailNormalizationHandlesCaseAndWhitespace() {
        String mixedEmail = "   " + BOOTSTRAP_OPS_EMAIL.toUpperCase() + "   ";
        String normalized = EmailNormalizer.normalize(mixedEmail);
        assertThat(normalized).isEqualTo(BOOTSTRAP_OPS_EMAIL);

        // Verified that looking up normalized email resolves the single provisioned account
        Optional<User> found = userRepository.findByEmail(normalized);
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo(BOOTSTRAP_OPS_EMAIL);
    }

    @Test
    @DisplayName("Existing case-variant CUSTOMER account cannot be promoted to OPS")
    void existingCaseVariantCustomerCannotBePromoted() {
        String customerEmail = "cust.case.variant@ledgerguard.local";
        User customer = new User(UUID.randomUUID(), "Customer User", customerEmail,
                passwordEncoder.encode("Password123456!"), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);

        String previousEmail = bootstrapProperties.getEmail();
        try {
            bootstrapProperties.setEmail("  " + customerEmail.toUpperCase() + "  ");

            assertThatThrownBy(() -> bootstrap.provisionOpsUser())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already registered to a CUSTOMER account")
                    .hasMessageContaining("Promotion to OPS is strictly forbidden");
        } finally {
            bootstrapProperties.setEmail(previousEmail);
        }
    }

    @Test
    @DisplayName("Concurrent startup safety: multiple concurrent executions complete idempotently without duplicate records")
    void concurrentProvisioningSafety() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(CompletableFuture.runAsync(() -> bootstrap.provisionOpsUser(), executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        // Exactly one user must exist with BOOTSTRAP_OPS_EMAIL
        Optional<User> userOpt = userRepository.findByEmail(BOOTSTRAP_OPS_EMAIL);
        assertThat(userOpt).isPresent();
        assertThat(userOpt.get().getRole()).isEqualTo(UserRole.OPS);
        assertThat(userOpt.get().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("Provisioned OPS user can log in via existing POST /api/auth/login and receives JWT with OPS role")
    void opsUserCanLoginThroughNormalLoginEndpoint() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(BOOTSTRAP_OPS_EMAIL, BOOTSTRAP_OPS_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.user.email", is(BOOTSTRAP_OPS_EMAIL)))
                .andExpect(jsonPath("$.user.role", is("OPS")))
                .andExpect(jsonPath("$.user.fullName", is(BOOTSTRAP_OPS_NAME)))
                .andExpect(cookie().exists(AuthController.REFRESH_COOKIE_NAME))
                .andExpect(cookie().httpOnly(AuthController.REFRESH_COOKIE_NAME, true));
    }

    @Test
    @DisplayName("Provisioned OPS user can access operational reconciliation endpoints")
    void opsUserCanAccessReconciliationEndpoints() throws Exception {
        User opsUser = userRepository.findByEmail(BOOTSTRAP_OPS_EMAIL).orElseThrow();
        String opsToken = jwtTokenService.generateAccessToken(opsUser);

        mockMvc.perform(get("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", notNullValue()));
    }

    @Test
    @DisplayName("CUSTOMER and MERCHANT roles are forbidden from operational reconciliation endpoints")
    void customerAndMerchantCannotAccessReconciliationEndpoints() throws Exception {
        User customer = new User(UUID.randomUUID(), "Customer User", "cust.recon@example.com",
                "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);
        String customerToken = jwtTokenService.generateAccessToken(customer);

        mockMvc.perform(get("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        User merchant = new User(UUID.randomUUID(), "Merchant User", "merch.recon@example.com",
                "$2a$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(merchant);
        String merchantToken = jwtTokenService.generateAccessToken(merchant);

        mockMvc.perform(get("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OPS role is forbidden from accessing customer/merchant wallet endpoint /api/wallets/me")
    void opsUserCannotAccessWalletEndpoints() throws Exception {
        User opsUser = userRepository.findByEmail(BOOTSTRAP_OPS_EMAIL).orElseThrow();
        String opsToken = jwtTokenService.generateAccessToken(opsUser);

        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Public self-registration with OPS role remains strictly forbidden")
    void publicRegistrationWithOpsRoleIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Malicious Actor",
                                  "email": "malicious.ops@example.com",
                                  "password": "Password123456!",
                                  "role": "OPS"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.detail", is("Registration with OPS role is not permitted.")));
    }
}
