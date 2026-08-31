package com.ledgerguard.ledger.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.api.dto.RegisterRequest;
import com.ledgerguard.identity.api.dto.UserSummaryResponse;
import com.ledgerguard.identity.application.AuthService;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.domain.Wallet;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletProvisioningIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WalletProvisioningService walletProvisioningService;

    @Autowired
    private WalletQueryService walletQueryService;

    @Autowired
    private AuthService authService;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Provisioning CUSTOMER wallet creates active INR ledger account and zero balance snapshot")
    void provisionCustomerWallet() {
        UUID userId = createTestUser(UserRole.CUSTOMER);

        LedgerAccount account = walletProvisioningService.provisionWallet(userId, UserRole.CUSTOMER);

        assertThat(account.getId()).isNotNull();
        assertThat(account.getOwnerUserId()).isEqualTo(userId);
        assertThat(account.getAccountType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.getCurrency()).isEqualTo("INR");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        // Verify zero balance snapshot automatically created by DB trigger
        LedgerBalanceSnapshot snapshot = ledgerBalanceSnapshotRepository.findById(account.getId()).orElseThrow();
        assertThat(snapshot.getBalanceMinor()).isEqualTo(0L);
        assertThat(snapshot.getUpdatedAt()).isNotNull();

        // Verify WalletQueryService returns projection
        Wallet wallet = walletQueryService.findWalletByUserId(userId).orElseThrow();
        assertThat(wallet.ledgerAccountId()).isEqualTo(account.getId());
        assertThat(wallet.balance().getMinorUnits()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Provisioning MERCHANT wallet creates active INR merchant ledger account and zero snapshot")
    void provisionMerchantWallet() {
        UUID userId = createTestUser(UserRole.MERCHANT);

        LedgerAccount account = walletProvisioningService.provisionWallet(userId, UserRole.MERCHANT);

        assertThat(account.getAccountType()).isEqualTo(AccountType.MERCHANT);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        LedgerBalanceSnapshot snapshot = ledgerBalanceSnapshotRepository.findById(account.getId()).orElseThrow();
        assertThat(snapshot.getBalanceMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Attempting to provision a wallet for an OPS user is rejected")
    void provisionOpsWalletRejected() {
        UUID userId = createTestUser(UserRole.OPS);

        assertThatThrownBy(() -> walletProvisioningService.provisionWallet(userId, UserRole.OPS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPS users cannot be provisioned with a wallet");

        assertThat(ledgerAccountRepository.findByOwnerUserId(userId)).isEmpty();
    }

    @Test
    @DisplayName("Duplicate wallet provisioning for the same user returns existing account without duplicates")
    void duplicateWalletProvisioningIdempotent() {
        UUID userId = createTestUser(UserRole.CUSTOMER);

        LedgerAccount first = walletProvisioningService.provisionWallet(userId, UserRole.CUSTOMER);
        LedgerAccount second = walletProvisioningService.provisionWallet(userId, UserRole.CUSTOMER);

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(ledgerAccountRepository.findByOwnerUserId(userId)).hasSize(1);
    }

    @Test
    @DisplayName("Partial unique index prevents creating multiple ledger accounts for the same user in database")
    void databasePreventsDuplicateOwnedAccounts() {
        UUID userId = createTestUser(UserRole.CUSTOMER);
        walletProvisioningService.provisionWallet(userId, UserRole.CUSTOMER);

        // Attempt direct insertion of a second account for the same user
        LedgerAccount duplicate = LedgerAccount.createCustomerAccount(userId);
        assertThatThrownBy(() -> ledgerAccountRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("AuthService registration provisions user and wallet atomically in one transaction")
    void authRegistrationProvisionsWalletAtomically() {
        String email = "wallet_user_" + UUID.randomUUID() + "@example.com";
        RegisterRequest request = new RegisterRequest(email, "SecurePassword123!", UserRole.CUSTOMER);

        UserSummaryResponse response = authService.register(request);

        assertThat(response.id()).isNotNull();
        List<LedgerAccount> accounts = ledgerAccountRepository.findByOwnerUserId(response.id());
        assertThat(accounts).hasSize(1);

        LedgerAccount account = accounts.get(0);
        assertThat(account.getAccountType()).isEqualTo(AccountType.CUSTOMER);

        LedgerBalanceSnapshot snapshot = ledgerBalanceSnapshotRepository.findById(account.getId()).orElseThrow();
        assertThat(snapshot.getBalanceMinor()).isEqualTo(0L);

        Wallet wallet = walletQueryService.findWalletByUserId(response.id()).orElseThrow();
        assertThat(wallet.balance().getMinorUnits()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Concurrent wallet provisioning for the same user yields exactly one account and snapshot")
    void concurrentWalletProvisioningResultsInSingleAccount() throws Exception {
        UUID userId = createTestUser(UserRole.CUSTOMER);

        int threadCount = 8;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threadCount);
        java.util.List<java.util.concurrent.Future<LedgerAccount>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                try {
                    return walletProvisioningService.provisionWallet(userId, UserRole.CUSTOMER);
                } catch (DataIntegrityViolationException e) {
                    // Concurrent race lost: return the winning wallet account
                    return walletProvisioningService.provisionWallet(userId, UserRole.CUSTOMER);
                }
            }));
        }

        for (java.util.concurrent.Future<LedgerAccount> future : futures) {
            LedgerAccount account = future.get();
            assertThat(account).isNotNull();
            assertThat(account.getOwnerUserId()).isEqualTo(userId);
        }
        executor.shutdown();

        // Exactly one owned ledger account and one snapshot exist
        List<LedgerAccount> accounts = ledgerAccountRepository.findByOwnerUserId(userId);
        assertThat(accounts).hasSize(1);
        assertThat(ledgerBalanceSnapshotRepository.findById(accounts.get(0).getId())).isPresent();
    }

    private UUID createTestUser(UserRole role) {
        UUID id = UUID.randomUUID();
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, "wallet_test." + id + "@example.com", "$2a$10$dummyHashValueForTestingPurposeOnly", role.name(), "ACTIVE", now, now
        );
        return id;
    }
}
