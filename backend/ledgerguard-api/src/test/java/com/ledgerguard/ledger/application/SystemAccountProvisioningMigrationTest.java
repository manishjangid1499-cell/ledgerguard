package com.ledgerguard.ledger.application;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DisplayName("V19 system account provisioning Flyway migration tests")
class SystemAccountProvisioningMigrationTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String RUNTIME_DB_PASSWORD = generateRuntimeSecret();

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-alpine")
            .withDatabaseName("ledgerguard_test")
            .withUsername("ledgerguard_app")
            .withPassword(RUNTIME_DB_PASSWORD)
            .withCommand("postgres", "-c", "max_connections=300");

    private static String generateRuntimeSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private DriverManagerDataSource createIsolatedDatabase(String dbName) {
        DriverManagerDataSource rootDs = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        rootDs.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate rootJdbc = new JdbcTemplate(rootDs);
        rootJdbc.execute("CREATE DATABASE " + dbName);

        String isolatedUrl = postgres.getJdbcUrl().replaceAll("/[^/?]+(\\?.*)?$", "/" + dbName + "$1");
        DriverManagerDataSource testDs = new DriverManagerDataSource(
                isolatedUrl, postgres.getUsername(), postgres.getPassword()
        );
        testDs.setDriverClassName("org.postgresql.Driver");
        return testDs;
    }

    @Test
    @DisplayName("Fresh database provisioning: V19 creates exactly 3 active INR system accounts with zero snapshots")
    void freshDatabaseProvisioning() {
        DriverManagerDataSource ds = createIsolatedDatabase("lg_fresh_" + UUID.randomUUID().toString().replace("-", ""));

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("19")
                .load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        List<Map<String, Object>> systemAccounts = jdbc.queryForList(
                "SELECT id, account_type, currency, status, owner_user_id FROM ledger_accounts " +
                "WHERE account_type IN ('PSP_CLEARING', 'PLATFORM_FEES', 'PLATFORM_RESERVE') " +
                "ORDER BY account_type ASC"
        );

        assertThat(systemAccounts).hasSize(3);

        for (Map<String, Object> account : systemAccounts) {
            UUID accountId = (UUID) account.get("id");
            assertThat(account.get("owner_user_id")).isNull();
            assertThat(account.get("currency")).isEqualTo("INR");
            assertThat(account.get("status")).isEqualTo("ACTIVE");

            Long balance = jdbc.queryForObject(
                    "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?",
                    Long.class, accountId
            );
            assertThat(balance).isZero();
        }

        List<String> types = systemAccounts.stream()
                .map(a -> (String) a.get("account_type"))
                .toList();
        assertThat(types).containsExactly("PLATFORM_FEES", "PLATFORM_RESERVE", "PSP_CLEARING");
    }

    @Test
    @DisplayName("Upgrading a V18 database with existing valid system accounts: preserves original account IDs")
    void upgradingV18DatabaseWithExistingValidAccounts() {
        DriverManagerDataSource ds = createIsolatedDatabase("lg_upg_" + UUID.randomUUID().toString().replace("-", ""));

        Flyway flywayV18 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("18")
                .load();
        flywayV18.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Timestamp now = Timestamp.from(Instant.now());

        UUID existingClearingId = UUID.randomUUID();
        UUID existingFeesId = UUID.randomUUID();
        UUID existingReserveId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PSP_CLEARING', 'INR', 'ACTIVE', ?, ?)",
                existingClearingId, now, now
        );
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PLATFORM_FEES', 'INR', 'ACTIVE', ?, ?)",
                existingFeesId, now, now
        );
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PLATFORM_RESERVE', 'INR', 'ACTIVE', ?, ?)",
                existingReserveId, now, now
        );

        Flyway flywayV19 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("19")
                .load();
        flywayV19.migrate();

        List<UUID> currentIds = jdbc.queryForList(
                "SELECT id FROM ledger_accounts WHERE account_type IN ('PSP_CLEARING', 'PLATFORM_FEES', 'PLATFORM_RESERVE') " +
                "ORDER BY created_at ASC",
                UUID.class
        );
        assertThat(currentIds).containsExactlyInAnyOrder(existingClearingId, existingFeesId, existingReserveId);
        assertThat(currentIds).hasSize(3);
    }

    @Test
    @DisplayName("Preservation of existing IDs, balances, and balanced POSTED journals across V18 to V19 migration")
    void preservationOfExistingIdsAndNonzeroBalances() {
        DriverManagerDataSource ds = createIsolatedDatabase("lg_bal_" + UUID.randomUUID().toString().replace("-", ""));

        Flyway flywayV18 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("18")
                .load();
        flywayV18.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Instant fixedInstant = Instant.parse("2026-09-01T10:00:00Z");
        Timestamp fixedCreatedAt = Timestamp.from(fixedInstant);
        Timestamp fixedUpdatedAt = Timestamp.from(fixedInstant);

        // 1. Create a customer user and wallet
        UUID customerUserId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                customerUserId, "cust-" + customerUserId + "@example.com", fixedCreatedAt, fixedUpdatedAt
        );

        UUID customerAccountId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                customerAccountId, customerUserId, fixedCreatedAt, fixedUpdatedAt
        );

        // 2. Create the three system accounts
        UUID clearingId = UUID.randomUUID();
        UUID feesId = UUID.randomUUID();
        UUID reserveId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PSP_CLEARING', 'INR', 'ACTIVE', ?, ?)",
                clearingId, fixedCreatedAt, fixedUpdatedAt
        );
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PLATFORM_FEES', 'INR', 'ACTIVE', ?, ?)",
                feesId, fixedCreatedAt, fixedUpdatedAt
        );
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PLATFORM_RESERVE', 'INR', 'ACTIVE', ?, ?)",
                reserveId, fixedCreatedAt, fixedUpdatedAt
        );

        // 3. Post a valid double-entry journal transaction following exact DB invariants:
        //    Insert DRAFT journal transaction -> insert balanced entries -> update status to POSTED
        //    Transaction: Fund customer 50000 INR from PSP_CLEARING:
        //      DEBIT  PSP_CLEARING (50000)
        //      CREDIT CUSTOMER     (50000)
        UUID fundingTxnId = UUID.randomUUID();
        Timestamp postedAt1 = Timestamp.from(fixedInstant.plusSeconds(60));
        jdbc.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) VALUES (?, 'DRAFT', 'INR', ?, NULL)",
                fundingTxnId, fixedCreatedAt
        );
        jdbc.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'DEBIT', 50000)",
                UUID.randomUUID(), fundingTxnId, clearingId
        );
        jdbc.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'CREDIT', 50000)",
                UUID.randomUUID(), fundingTxnId, customerAccountId
        );
        jdbc.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                postedAt1, fundingTxnId
        );

        //    Transaction: Fee transfer 25000 INR from PLATFORM_RESERVE to PLATFORM_FEES:
        //      DEBIT  PLATFORM_RESERVE (25000)
        //      CREDIT PLATFORM_FEES    (25000)
        UUID feeTxnId = UUID.randomUUID();
        Timestamp postedAt2 = Timestamp.from(fixedInstant.plusSeconds(120));
        jdbc.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) VALUES (?, 'DRAFT', 'INR', ?, NULL)",
                feeTxnId, fixedCreatedAt
        );
        jdbc.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'DEBIT', 25000)",
                UUID.randomUUID(), feeTxnId, reserveId
        );
        jdbc.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'CREDIT', 25000)",
                UUID.randomUUID(), feeTxnId, feesId
        );
        jdbc.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                postedAt2, feeTxnId
        );

        // Capture V18 baseline state completely
        Map<String, Object> clearingV18 = jdbc.queryForMap("SELECT * FROM ledger_accounts WHERE id = ?", clearingId);
        Map<String, Object> feesV18 = jdbc.queryForMap("SELECT * FROM ledger_accounts WHERE id = ?", feesId);
        Map<String, Object> reserveV18 = jdbc.queryForMap("SELECT * FROM ledger_accounts WHERE id = ?", reserveId);
        Map<String, Object> customerAccV18 = jdbc.queryForMap("SELECT * FROM ledger_accounts WHERE id = ?", customerAccountId);

        Map<String, Object> clearingSnapV18 = jdbc.queryForMap("SELECT * FROM ledger_balance_snapshots WHERE ledger_account_id = ?", clearingId);
        Map<String, Object> feesSnapV18 = jdbc.queryForMap("SELECT * FROM ledger_balance_snapshots WHERE ledger_account_id = ?", feesId);
        Map<String, Object> reserveSnapV18 = jdbc.queryForMap("SELECT * FROM ledger_balance_snapshots WHERE ledger_account_id = ?", reserveId);
        Map<String, Object> customerSnapV18 = jdbc.queryForMap("SELECT * FROM ledger_balance_snapshots WHERE ledger_account_id = ?", customerAccountId);

        List<Map<String, Object>> txnsV18 = jdbc.queryForList("SELECT * FROM journal_transactions ORDER BY id ASC");
        List<Map<String, Object>> entriesV18 = jdbc.queryForList("SELECT * FROM journal_entries ORDER BY id ASC");

        // Verify pre-migration balances match posted journal calculations
        assertThat(clearingSnapV18.get("balance_minor")).isEqualTo(50000L);
        assertThat(feesSnapV18.get("balance_minor")).isEqualTo(25000L);
        assertThat(reserveSnapV18.get("balance_minor")).isEqualTo(25000L);
        assertThat(customerSnapV18.get("balance_minor")).isEqualTo(50000L);

        // Migrate from V18 to V19
        Flyway flywayV19 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("19")
                .load();
        flywayV19.migrate();

        // Verify all accounts, timestamps, snapshots, and journal history remain byte-for-byte / value-for-value identical
        Map<String, Object> clearingV19 = jdbc.queryForMap("SELECT * FROM ledger_accounts WHERE id = ?", clearingId);
        Map<String, Object> feesV19 = jdbc.queryForMap("SELECT * FROM ledger_accounts WHERE id = ?", feesId);
        Map<String, Object> reserveV19 = jdbc.queryForMap("SELECT * FROM ledger_accounts WHERE id = ?", reserveId);
        Map<String, Object> customerAccV19 = jdbc.queryForMap("SELECT * FROM ledger_accounts WHERE id = ?", customerAccountId);

        assertThat(clearingV19).isEqualTo(clearingV18);
        assertThat(feesV19).isEqualTo(feesV18);
        assertThat(reserveV19).isEqualTo(reserveV18);
        assertThat(customerAccV19).isEqualTo(customerAccV18);

        Map<String, Object> clearingSnapV19 = jdbc.queryForMap("SELECT * FROM ledger_balance_snapshots WHERE ledger_account_id = ?", clearingId);
        Map<String, Object> feesSnapV19 = jdbc.queryForMap("SELECT * FROM ledger_balance_snapshots WHERE ledger_account_id = ?", feesId);
        Map<String, Object> reserveSnapV19 = jdbc.queryForMap("SELECT * FROM ledger_balance_snapshots WHERE ledger_account_id = ?", reserveId);
        Map<String, Object> customerSnapV19 = jdbc.queryForMap("SELECT * FROM ledger_balance_snapshots WHERE ledger_account_id = ?", customerAccountId);

        assertThat(clearingSnapV19).isEqualTo(clearingSnapV18);
        assertThat(feesSnapV19).isEqualTo(feesSnapV18);
        assertThat(reserveSnapV19).isEqualTo(reserveSnapV18);
        assertThat(customerSnapV19).isEqualTo(customerSnapV18);

        List<Map<String, Object>> txnsV19 = jdbc.queryForList("SELECT * FROM journal_transactions ORDER BY id ASC");
        List<Map<String, Object>> entriesV19 = jdbc.queryForList("SELECT * FROM journal_entries ORDER BY id ASC");

        assertThat(txnsV19).isEqualTo(txnsV18);
        assertThat(entriesV19).isEqualTo(entriesV18);
    }

    @Test
    @DisplayName("Partially provisioned database: creates only missing accounts and preserves existing ones")
    void partiallyProvisionedDatabase() {
        DriverManagerDataSource ds = createIsolatedDatabase("lg_part_" + UUID.randomUUID().toString().replace("-", ""));

        Flyway flywayV18 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("18")
                .load();
        flywayV18.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Timestamp now = Timestamp.from(Instant.now());

        UUID existingReserveId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PLATFORM_RESERVE', 'INR', 'ACTIVE', ?, ?)",
                existingReserveId, now, now
        );
        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = 75000 WHERE ledger_account_id = ?", existingReserveId);

        Flyway flywayV19 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("19")
                .load();
        flywayV19.migrate();

        List<Map<String, Object>> systemAccounts = jdbc.queryForList(
                "SELECT id, account_type, status FROM ledger_accounts " +
                "WHERE account_type IN ('PSP_CLEARING', 'PLATFORM_FEES', 'PLATFORM_RESERVE') " +
                "ORDER BY account_type ASC"
        );
        assertThat(systemAccounts).hasSize(3);

        Map<String, Object> reserveAcc = systemAccounts.stream()
                .filter(a -> "PLATFORM_RESERVE".equals(a.get("account_type")))
                .findFirst().orElseThrow();
        assertThat(reserveAcc.get("id")).isEqualTo(existingReserveId);
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, existingReserveId))
                .isEqualTo(75000L);

        Map<String, Object> clearingAcc = systemAccounts.stream()
                .filter(a -> "PSP_CLEARING".equals(a.get("account_type")))
                .findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, clearingAcc.get("id")))
                .isZero();

        Map<String, Object> feesAcc = systemAccounts.stream()
                .filter(a -> "PLATFORM_FEES".equals(a.get("account_type")))
                .findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, feesAcc.get("id")))
                .isZero();
    }

    @Test
    @DisplayName("Duplicate account rejection and transactional rollback: inserts earlier account then rolls back completely")
    void duplicateAccountRejectionAndRollback() {
        DriverManagerDataSource ds = createIsolatedDatabase("lg_dup_" + UUID.randomUUID().toString().replace("-", ""));

        Flyway flywayV18 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("18")
                .load();
        flywayV18.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Timestamp now = Timestamp.from(Instant.now());

        // Baseline: No system accounts exist initially, so iteration order is:
        // 1. PSP_CLEARING -> absent, inserted first
        // 2. PLATFORM_FEES -> absent, inserted second
        // 3. PLATFORM_RESERVE -> has duplicate rows below, triggers exception
        UUID reserve1 = UUID.randomUUID();
        UUID reserve2 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PLATFORM_RESERVE', 'INR', 'ACTIVE', ?, ?)",
                reserve1, now, now
        );
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PLATFORM_RESERVE', 'INR', 'ACTIVE', ?, ?)",
                reserve2, now, now
        );

        List<Map<String, Object>> baselineAccounts = jdbc.queryForList("SELECT * FROM ledger_accounts ORDER BY id ASC");
        List<Map<String, Object>> baselineSnapshots = jdbc.queryForList("SELECT * FROM ledger_balance_snapshots ORDER BY ledger_account_id ASC");
        assertThat(baselineAccounts).hasSize(2);
        assertThat(baselineSnapshots).hasSize(2);

        Flyway flywayV19 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("19")
                .load();

        assertThatThrownBy(flywayV19::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("duplicate accounts found for type PLATFORM_RESERVE");

        // Assert Flyway V19 was not applied
        Integer v19Applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '19' AND success = true",
                Integer.class
        );
        assertThat(v19Applied).isZero();

        // Assert earlier inserted accounts (PSP_CLEARING, PLATFORM_FEES) AND their snapshots are ABSENT
        Integer clearingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_accounts WHERE account_type = 'PSP_CLEARING'",
                Integer.class
        );
        assertThat(clearingCount).isZero();

        Integer feesCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_accounts WHERE account_type = 'PLATFORM_FEES'",
                Integer.class
        );
        assertThat(feesCount).isZero();

        // Assert snapshots for clearing and fees were rolled back completely
        Integer extraSnapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_balance_snapshots WHERE ledger_account_id NOT IN (?, ?)",
                Integer.class, reserve1, reserve2
        );
        assertThat(extraSnapshots).isZero();

        // Assert original records remain exactly unchanged
        List<Map<String, Object>> postRollbackAccounts = jdbc.queryForList("SELECT * FROM ledger_accounts ORDER BY id ASC");
        List<Map<String, Object>> postRollbackSnapshots = jdbc.queryForList("SELECT * FROM ledger_balance_snapshots ORDER BY ledger_account_id ASC");

        assertThat(postRollbackAccounts).isEqualTo(baselineAccounts);
        assertThat(postRollbackSnapshots).isEqualTo(baselineSnapshots);
    }

    @Test
    @DisplayName("Invalid account rejection and transactional rollback: earlier inserted accounts are rolled back")
    void invalidAccountRejectionAndRollback() {
        DriverManagerDataSource ds = createIsolatedDatabase("lg_inv_" + UUID.randomUUID().toString().replace("-", ""));

        Flyway flywayV18 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("18")
                .load();
        flywayV18.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Timestamp now = Timestamp.from(Instant.now());

        // Arrange: No PSP_CLEARING exists (so it will be inserted first during migration).
        // Later type PLATFORM_FEES exists with invalid status 'CLOSED'.
        UUID invalidFeesId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                "VALUES (?, NULL, 'PLATFORM_FEES', 'INR', 'CLOSED', ?, ?)",
                invalidFeesId, now, now
        );

        List<Map<String, Object>> baselineAccounts = jdbc.queryForList("SELECT * FROM ledger_accounts ORDER BY id ASC");
        List<Map<String, Object>> baselineSnapshots = jdbc.queryForList("SELECT * FROM ledger_balance_snapshots ORDER BY ledger_account_id ASC");
        assertThat(baselineAccounts).hasSize(1);
        assertThat(baselineSnapshots).hasSize(1);

        Flyway flywayV19 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("19")
                .load();

        assertThatThrownBy(flywayV19::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("invalid existing account found for type PLATFORM_FEES");

        // Assert Flyway V19 was not applied
        Integer v19Applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '19' AND success = true",
                Integer.class
        );
        assertThat(v19Applied).isZero();

        // Assert PSP_CLEARING was not committed
        Integer clearingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_accounts WHERE account_type = 'PSP_CLEARING'",
                Integer.class
        );
        assertThat(clearingCount).isZero();

        // Assert snapshots for earlier inserted accounts are absent
        Integer nonFeesSnapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_balance_snapshots WHERE ledger_account_id != ?",
                Integer.class, invalidFeesId
        );
        assertThat(nonFeesSnapshots).isZero();

        // Assert original records remain unchanged
        List<Map<String, Object>> postRollbackAccounts = jdbc.queryForList("SELECT * FROM ledger_accounts ORDER BY id ASC");
        List<Map<String, Object>> postRollbackSnapshots = jdbc.queryForList("SELECT * FROM ledger_balance_snapshots ORDER BY ledger_account_id ASC");

        assertThat(postRollbackAccounts).isEqualTo(baselineAccounts);
        assertThat(postRollbackSnapshots).isEqualTo(baselineSnapshots);
    }

    @Test
    @DisplayName("Repeated application startup without additional accounts: idempotent migration")
    void repeatedStartupIdempotency() {
        DriverManagerDataSource ds = createIsolatedDatabase("lg_idem_" + UUID.randomUUID().toString().replace("-", ""));

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("19")
                .load();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        List<UUID> initialAccountIds = jdbc.queryForList(
                "SELECT id FROM ledger_accounts WHERE account_type IN ('PSP_CLEARING', 'PLATFORM_FEES', 'PLATFORM_RESERVE') " +
                "ORDER BY created_at ASC, id ASC",
                UUID.class
        );
        assertThat(initialAccountIds).hasSize(3);

        // Run migration again on the same database
        Flyway flyway2 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("19")
                .load();
        flyway2.migrate();

        List<UUID> postSecondMigrationIds = jdbc.queryForList(
                "SELECT id FROM ledger_accounts WHERE account_type IN ('PSP_CLEARING', 'PLATFORM_FEES', 'PLATFORM_RESERVE') " +
                "ORDER BY created_at ASC, id ASC",
                UUID.class
        );

        assertThat(postSecondMigrationIds).isEqualTo(initialAccountIds);

        Integer totalSystemAccounts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_accounts WHERE account_type IN ('PSP_CLEARING', 'PLATFORM_FEES', 'PLATFORM_RESERVE')",
                Integer.class
        );
        assertThat(totalSystemAccounts).isEqualTo(3);
    }
}