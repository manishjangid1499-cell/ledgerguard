package com.ledgerguard.ledger;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerSchemaAndConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Database contains only authorized tables; no future financial tables exist")
    void databaseContainsOnlyAuthorizedTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class
        );

        assertThat(tables).contains(
                "users",
                "refresh_tokens",
                "flyway_schema_history",
                "ledger_accounts",
                "journal_transactions",
                "journal_entries",
                "ledger_balance_snapshots",
                "idempotency_records",
                "transfers",
                "payments",
                "refunds",
                "balance_holds",
                "outbox_events"
        );

        assertThat(tables).doesNotContain(
                "wallets",
                "account_balances"
        );
    }

    @Test
    @DisplayName("Unsupported account type is rejected by PostgreSQL check constraint")
    void unsupportedAccountTypeIsRejected() {
        UUID userId = createTestUser();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "SAVINGS", "INR", "ACTIVE", now, now
        )).hasMessageContaining("chk_ledger_accounts_account_type");
    }

    @Test
    @DisplayName("Unsupported account status is rejected by PostgreSQL check constraint")
    void unsupportedAccountStatusIsRejected() {
        UUID userId = createTestUser();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "CUSTOMER", "INR", "FROZEN", now, now
        )).hasMessageContaining("chk_ledger_accounts_status");
    }

    @Test
    @DisplayName("Non-INR currency in ledger accounts is rejected by PostgreSQL check constraint")
    void nonInrCurrencyIsRejected() {
        UUID userId = createTestUser();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "CUSTOMER", "USD", "ACTIVE", now, now
        )).hasMessageContaining("chk_ledger_accounts_currency");
    }

    @Test
    @DisplayName("CUSTOMER or MERCHANT account without owner is rejected by PostgreSQL ownership check constraint")
    void userAccountWithoutOwnerIsRejected() {
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), null, "CUSTOMER", "INR", "ACTIVE", now, now
        )).hasMessageContaining("chk_ledger_accounts_ownership");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), null, "MERCHANT", "INR", "ACTIVE", now, now
        )).hasMessageContaining("chk_ledger_accounts_ownership");
    }

    @Test
    @DisplayName("System account with owner is rejected by PostgreSQL ownership check constraint")
    void systemAccountWithOwnerIsRejected() {
        UUID userId = createTestUser();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "PSP_CLEARING", "INR", "ACTIVE", now, now
        )).hasMessageContaining("chk_ledger_accounts_ownership");
    }

    @Test
    @DisplayName("Invalid owner FK is rejected by PostgreSQL foreign key constraint")
    void invalidOwnerFkIsRejected() {
        Timestamp now = Timestamp.from(Instant.now());
        UUID nonexistentUserId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), nonexistentUserId, "CUSTOMER", "INR", "ACTIVE", now, now
        )).hasMessageContaining("fk_ledger_accounts_owner_user_id");
    }

    @Test
    @DisplayName("Journal entry with zero amount is rejected by PostgreSQL check constraint")
    void zeroAmountEntryIsRejected() {
        UUID accountId = createTestSystemAccount("PLATFORM_RESERVE");
        UUID txnId = createTestDraftTransaction();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), txnId, accountId, "DEBIT", 0L
        )).hasMessageContaining("chk_journal_entries_amount_minor");
    }

    @Test
    @DisplayName("Journal entry with negative amount is rejected by PostgreSQL check constraint")
    void negativeAmountEntryIsRejected() {
        UUID accountId = createTestSystemAccount("PLATFORM_RESERVE");
        UUID txnId = createTestDraftTransaction();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), txnId, accountId, "DEBIT", -500L
        )).hasMessageContaining("chk_journal_entries_amount_minor");
    }

    @Test
    @DisplayName("Journal entry with invalid direction is rejected by PostgreSQL check constraint")
    void invalidDirectionIsRejected() {
        UUID accountId = createTestSystemAccount("PLATFORM_RESERVE");
        UUID txnId = createTestDraftTransaction();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), txnId, accountId, "TRANSFER", 1000L
        )).hasMessageContaining("chk_journal_entries_direction");
    }

    @Test
    @DisplayName("Deleting a user with an associated ledger account is restricted and fails")
    void userDeletionRestrictedWhenLedgerAccountExists() {
        UUID userId = createTestUser();
        Timestamp now = Timestamp.from(Instant.now());
        UUID accountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                accountId, userId, "CUSTOMER", "INR", "ACTIVE", now, now
        );

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId))
                .hasMessageContaining("fk_ledger_accounts_owner_user_id");
    }

    private UUID createTestUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, "user." + id + "@example.com", "$2a$10$dummyHashValueForTestingPurposeOnly", "CUSTOMER", "ACTIVE", now, now
        );
        return id;
    }

    private UUID createTestSystemAccount(String type) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, null, type, "INR", "ACTIVE", now, now
        );
        return id;
    }

    private UUID createTestDraftTransaction() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                id, "DRAFT", "INR", now, null
        );
        return id;
    }
}
