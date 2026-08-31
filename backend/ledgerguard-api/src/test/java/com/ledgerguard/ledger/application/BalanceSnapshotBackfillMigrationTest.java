package com.ledgerguard.ledger.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BalanceSnapshotBackfillMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-alpine")
            .withDatabaseName("ledgerguard_test")
            .withUsername("ledgerguard_app")
            .withPassword("test_password");

    @Test
    @DisplayName("Flyway V3 migration correctly backfills balance snapshots for pre-existing accounts from POSTED journals")
    void v3MigrationBackfillsExistingAccounts() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        dataSource.setDriverClassName("org.postgresql.Driver");

        // 1. Migrate schema up to V2 only
        Flyway flywayV2 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("2")
                .load();
        flywayV2.migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Timestamp now = Timestamp.from(Instant.now());

        // 2. Insert pre-existing user and accounts at schema version 2
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, 'backfill@example.com', 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                userId, now, now
        );

        UUID customerAccountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                customerAccountId, userId, now, now
        );

        UUID reserveAccountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, NULL, 'PLATFORM_RESERVE', 'INR', 'ACTIVE', ?, ?)",
                reserveAccountId, now, now
        );

        // 3. Insert a POSTED journal transaction with balanced entries
        UUID postedJournalId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                postedJournalId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', 15000)",
                UUID.randomUUID(), postedJournalId, customerAccountId
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', 15000)",
                UUID.randomUUID(), postedJournalId, reserveAccountId
        );
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, postedJournalId
        );

        // 4. Insert a DRAFT journal transaction (should NOT contribute to snapshot balance)
        UUID draftJournalId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                draftJournalId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', 5000)",
                UUID.randomUUID(), draftJournalId, customerAccountId
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', 5000)",
                UUID.randomUUID(), draftJournalId, reserveAccountId
        );

        // 5. Run Flyway migration to apply V3
        Flyway flywayV3 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("3")
                .load();
        flywayV3.migrate();

        // 6. Verify backfilled balance snapshot values
        Long customerBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?",
                Long.class, customerAccountId
        );
        Long reserveBalance = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?",
                Long.class, reserveAccountId
        );

        // Customer: 15000 CREDIT = +15000
        assertThat(customerBalance).isEqualTo(15000L);
        // Reserve: 15000 DEBIT = +15000
        assertThat(reserveBalance).isEqualTo(15000L);
    }
}
