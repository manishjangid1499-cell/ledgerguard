package com.ledgerguard.hold;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceHoldDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Test
    @DisplayName("Direct JDBC: Valid insert with ACTIVE status and capacity succeeds")
    void validInsertSucceeds() {
        LedgerAccount account = createWalletAccount(AccountType.CUSTOMER);
        fundAccountSnapshot(account.getId(), 10000L);

        UUID holdId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);

        int rows = jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                holdId, account.getId(), 5000L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        );

        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("Direct JDBC: Non-ACTIVE initial status on INSERT is rejected by trigger")
    void nonActiveInitialStatusIsRejected() {
        LedgerAccount account = createWalletAccount(AccountType.CUSTOMER);
        fundAccountSnapshot(account.getId(), 10000L);

        UUID holdId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);

        for (String invalidStatus : List.of("CONSUMED", "RELEASED", "EXPIRED")) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), account.getId(), 1000L, "INR", invalidStatus, Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
            )).isNotNull();
        }
    }

    @Test
    @DisplayName("Direct JDBC: Amount <= 0 and non-INR currency are rejected by check constraints")
    void amountAndCurrencyConstraintsEnforced() {
        LedgerAccount account = createWalletAccount(AccountType.CUSTOMER);
        fundAccountSnapshot(account.getId(), 10000L);

        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);

        // Negative amount
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.getId(), -500L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        )).isNotNull();

        // Zero amount
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.getId(), 0L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        )).isNotNull();

        // USD currency
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.getId(), 500L, "USD", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        )).isNotNull();
    }

    @Test
    @DisplayName("Direct JDBC: System account or CLOSED account is rejected by trigger")
    void systemOrClosedAccountRejected() {
        // System account
        LedgerAccount reserve = LedgerAccount.createSystemAccount(AccountType.PLATFORM_RESERVE);
        reserve = ledgerAccountRepository.saveAndFlush(reserve);
        fundAccountSnapshot(reserve.getId(), 50000L);

        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);

        UUID holdId1 = UUID.randomUUID();
        UUID reserveId = reserve.getId();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                holdId1, reserveId, 1000L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        )).hasMessageContaining("user wallet accounts");

        // Closed customer account
        User user = createTestUser("closed." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount closedAccount = new LedgerAccount(
                UUID.randomUUID(), user.getId(), AccountType.CUSTOMER, "INR", AccountStatus.CLOSED, now, now
        );
        closedAccount = ledgerAccountRepository.saveAndFlush(closedAccount);
        fundAccountSnapshot(closedAccount.getId(), 10000L);

        UUID holdId2 = UUID.randomUUID();
        UUID closedAccountId = closedAccount.getId();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                holdId2, closedAccountId, 1000L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        )).hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("Direct JDBC: Single insert exceeding posted balance is rejected by capacity trigger")
    void singleInsertExceedingCapacityRejected() {
        LedgerAccount account = createWalletAccount(AccountType.CUSTOMER);
        fundAccountSnapshot(account.getId(), 5000L);

        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.getId(), 5001L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        )).hasMessageContaining("Insufficient available balance");
    }

    @Test
    @DisplayName("Direct JDBC: Cumulative hold capacity enforced across multiple inserts (3000 + 4000 + 3000 on 10000, next 1 fails)")
    void cumulativeCapacityEnforced() {
        LedgerAccount account = createWalletAccount(AccountType.CUSTOMER);
        fundAccountSnapshot(account.getId(), 10000L);

        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);

        // 1. Insert 3000 -> success
        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.getId(), 3000L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        );

        // 2. Insert 4000 -> success (cumulative 7000)
        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.getId(), 4000L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        );

        // 3. Insert 3000 -> success (cumulative 10000, exact capacity)
        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.getId(), 3000L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        );

        // 4. Insert 1 -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), account.getId(), 1L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        )).hasMessageContaining("Insufficient available balance");

        Long totalActive = jdbcTemplate.queryForObject(
                "SELECT SUM(amount_minor) FROM balance_holds WHERE ledger_account_id = ? AND status = 'ACTIVE'",
                Long.class, account.getId()
        );
        assertThat(totalActive).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Direct JDBC Concurrency: Two concurrent 7000 hold inserts on 10000 posted balance yield 1 success, 1 failure, 7000 total")
    void directConcurrentHoldInsertsSerializedByTrigger() throws Exception {
        LedgerAccount account = createWalletAccount(AccountType.CUSTOMER);
        fundAccountSnapshot(account.getId(), 10000L);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Instant now = Instant.now();
                    Instant expiry = now.plus(1, ChronoUnit.HOURS);
                    jdbcTemplate.update(
                            "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), account.getId(), 7000L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
                    );
                    successes.incrementAndGet();
                } catch (Throwable t) {
                    failures.incrementAndGet();
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);

        Long totalActive = jdbcTemplate.queryForObject(
                "SELECT SUM(amount_minor) FROM balance_holds WHERE ledger_account_id = ? AND status = 'ACTIVE'",
                Long.class, account.getId()
        );
        assertThat(totalActive).isEqualTo(7000L);
    }

    @Test
    @DisplayName("Direct JDBC Immutability: UPDATE of immutable fields, illegal transitions, and DELETE are rejected")
    void immutabilityAndIllegalTransitionsRejected() {
        LedgerAccount account = createWalletAccount(AccountType.CUSTOMER);
        fundAccountSnapshot(account.getId(), 10000L);

        UUID holdId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiry = now.plus(1, ChronoUnit.HOURS);

        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                holdId, account.getId(), 5000L, "INR", "ACTIVE", Timestamp.from(expiry), Timestamp.from(now), Timestamp.from(now), null
        );

        // 1. Mutate amount -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE balance_holds SET amount_minor = 6000 WHERE id = ?", holdId
        )).hasMessageContaining("immutable");

        // 2. Mutate account_id -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE balance_holds SET ledger_account_id = ? WHERE id = ?", UUID.randomUUID(), holdId
        )).hasMessageContaining("immutable");

        // 3. Mutate currency -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE balance_holds SET currency = 'USD' WHERE id = ?", holdId
        )).isNotNull();

        // 4. Valid transition ACTIVE -> RELEASED succeeds
        int updated = jdbcTemplate.update(
                "UPDATE balance_holds SET status = 'RELEASED', terminal_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), holdId
        );
        assertThat(updated).isEqualTo(1);

        // 5. Transition from terminal RELEASED -> CONSUMED rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE balance_holds SET status = 'CONSUMED' WHERE id = ?", holdId
        )).hasMessageContaining("Cannot transition from terminal hold status");

        // 6. Transition from terminal RELEASED -> ACTIVE rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE balance_holds SET status = 'ACTIVE', terminal_at = NULL WHERE id = ?", holdId
        )).hasMessageContaining("Cannot transition from terminal hold status");

        // 7. DELETE rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM balance_holds WHERE id = ?", holdId
        )).hasMessageContaining("cannot be deleted");
    }

    private LedgerAccount createWalletAccount(AccountType type) {
        User user = createTestUser("hold.user." + UUID.randomUUID() + "@example.com",
                type == AccountType.CUSTOMER ? UserRole.CUSTOMER : UserRole.MERCHANT);
        LedgerAccount account = (type == AccountType.CUSTOMER)
                ? LedgerAccount.createCustomerAccount(user.getId())
                : LedgerAccount.createMerchantAccount(user.getId());
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private User createTestUser(String email, UserRole role) {
        User user = new User(UUID.randomUUID(), email, "$2a$10$hash", role, UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private void fundAccountSnapshot(UUID accountId, long amountMinor) {
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = ? WHERE ledger_account_id = ?",
                amountMinor, accountId
        );
    }
}
