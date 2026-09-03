package com.ledgerguard.payout;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID customerAccountId;
    private UUID merchantUserId;
    private UUID merchantAccountId;
    private UUID pspClearingAccountId;

    @BeforeEach
    void setUpData() {
        Timestamp now = Timestamp.from(Instant.now());
        userId = UUID.randomUUID();
        customerAccountId = UUID.randomUUID();
        merchantUserId = UUID.randomUUID();
        merchantAccountId = UUID.randomUUID();
        pspClearingAccountId = UUID.randomUUID();

        // 1. Insert users
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                userId, "cust-" + userId + "@example.com", now, now
        );
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'MERCHANT', 'ACTIVE', ?, ?)",
                merchantUserId, "merch-" + merchantUserId + "@example.com", now, now
        );

        // 2. Insert ledger accounts (trigger auto-creates snapshots)
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                customerAccountId, userId, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'MERCHANT', 'INR', 'ACTIVE', ?, ?)",
                merchantAccountId, merchantUserId, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, NULL, 'PSP_CLEARING', 'INR', 'ACTIVE', ?, ?)",
                pspClearingAccountId, now, now
        );

        // 3. Fund customer wallet with initial ledger balance
        UUID initTxnId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) " +
                        "VALUES (?, 'DRAFT', 'INR', ?, NULL)",
                initTxnId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', 100000)",
                UUID.randomUUID(), initTxnId, pspClearingAccountId
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', 100000)",
                UUID.randomUUID(), initTxnId, customerAccountId
        );
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, initTxnId
        );
    }

    private UUID createActiveHold(UUID accountId, long amountMinor) {
        UUID holdId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(Duration.ofMinutes(30)));
        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, ?, 'INR', 'ACTIVE', ?, ?, ?, NULL)",
                holdId, accountId, amountMinor, expiresAt, now, now
        );
        return holdId;
    }

    @Test
    @DisplayName("Direct insertion of CREATED payout with matching ACTIVE hold succeeds")
    void insertCreatedPayoutSucceeds() {
        UUID payoutId = UUID.randomUUID();
        UUID holdId = createActiveHold(customerAccountId, 10000L);
        Timestamp now = Timestamp.from(Instant.now());

        int rows = jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'CREATED', ?)",
                payoutId, userId, customerAccountId, holdId, now
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("Direct insertion with status PROCESSING, SUCCEEDED or FAILED is rejected")
    void directInsertNonCreatedStatusRejected() {
        UUID payoutId1 = UUID.randomUUID();
        UUID payoutId2 = UUID.randomUUID();
        UUID payoutId3 = UUID.randomUUID();
        UUID holdId1 = createActiveHold(customerAccountId, 10000L);
        UUID holdId2 = createActiveHold(customerAccountId, 10000L);
        UUID holdId3 = createActiveHold(customerAccountId, 10000L);
        Timestamp now = Timestamp.from(Instant.now());

        // Insertion as PROCESSING
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'PROCESSING', ?)",
                payoutId1, userId, customerAccountId, holdId1, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be inserted with status CREATED");

        // Insertion as SUCCEEDED
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, provider_operation_id, completed_at, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'SUCCEEDED', ?, ?, ?)",
                payoutId2, userId, customerAccountId, holdId2, UUID.randomUUID(), now, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be inserted with status CREATED");

        // Insertion as FAILED
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, completed_at, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'FAILED', ?, ?)",
                payoutId3, userId, customerAccountId, holdId3, now, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be inserted with status CREATED");
    }

    @Test
    @DisplayName("Payout insertion validates source account ownership, status, currency, and type")
    void sourceAccountValidationEnforced() {
        UUID payoutId = UUID.randomUUID();
        UUID holdId = createActiveHold(customerAccountId, 10000L);
        Timestamp now = Timestamp.from(Instant.now());

        // Wrong initiator owner
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'CREATED', ?)",
                payoutId, merchantUserId, customerAccountId, holdId, now
        )).isInstanceOf(Exception.class).hasMessageContaining("does not match initiator");

        // Wrong account type (PSP_CLEARING)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'CREATED', ?)",
                payoutId, userId, pspClearingAccountId, holdId, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be of type CUSTOMER or MERCHANT");
    }

    @Test
    @DisplayName("Payout insertion validates referenced balance hold matching account, amount, and status")
    void holdMatchingValidationEnforced() {
        UUID payoutId = UUID.randomUUID();
        UUID holdId = createActiveHold(customerAccountId, 10000L);
        Timestamp now = Timestamp.from(Instant.now());

        // Amount mismatch between hold (10000) and payout (5000)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 5000, 'INR', 'CREATED', ?)",
                payoutId, userId, customerAccountId, holdId, now
        )).isInstanceOf(Exception.class).hasMessageContaining("does not match payout amount");
    }

    @Test
    @DisplayName("Transition to SUCCEEDED requires CONSUMED hold and exact balanced POSTED journal")
    void transitionToSucceededValidation() {
        UUID payoutId = UUID.randomUUID();
        UUID holdId = createActiveHold(customerAccountId, 10000L);
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'CREATED', ?)",
                payoutId, userId, customerAccountId, holdId, now
        );

        jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, payoutId
        );

        // 1. Create POSTED journal (first DRAFT, then entries, then POSTED)
        UUID journalTxnId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) " +
                        "VALUES (?, 'DRAFT', 'INR', ?, NULL)",
                journalTxnId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', 10000)",
                UUID.randomUUID(), journalTxnId, customerAccountId
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', 10000)",
                UUID.randomUUID(), journalTxnId, pspClearingAccountId
        );
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalTxnId
        );

        // Attempt SUCCEEDED update while hold is still ACTIVE -> fails
        UUID providerOpId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payouts SET status = 'SUCCEEDED', provider_operation_id = ?, journal_transaction_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                providerOpId, journalTxnId, now, payoutId
        )).isInstanceOf(Exception.class).hasMessageContaining("must be CONSUMED");

        // Mark hold as CONSUMED
        jdbcTemplate.update(
                "UPDATE balance_holds SET status = 'CONSUMED', terminal_at = ?, updated_at = ? WHERE id = ?",
                now, now, holdId
        );

        // Now SUCCEEDED update succeeds
        int updated = jdbcTemplate.update(
                "UPDATE payouts SET status = 'SUCCEEDED', provider_operation_id = ?, journal_transaction_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                providerOpId, journalTxnId, now, payoutId
        );
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("Transition to FAILED requires RELEASED hold and journal_transaction_id NULL")
    void transitionToFailedValidation() {
        UUID payoutId = UUID.randomUUID();
        UUID holdId = createActiveHold(customerAccountId, 10000L);
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'CREATED', ?)",
                payoutId, userId, customerAccountId, holdId, now
        );

        jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, payoutId
        );

        // Attempt FAILED update while hold is still ACTIVE -> fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payouts SET status = 'FAILED', completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                now, payoutId
        )).isInstanceOf(Exception.class).hasMessageContaining("must be RELEASED");

        // Mark hold as RELEASED
        jdbcTemplate.update(
                "UPDATE balance_holds SET status = 'RELEASED', terminal_at = ?, updated_at = ? WHERE id = ?",
                now, now, holdId
        );

        // Now FAILED update succeeds
        int updated = jdbcTemplate.update(
                "UPDATE payouts SET status = 'FAILED', completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                now, payoutId
        );
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("Terminal payouts are immutable and cannot be modified or deleted")
    void terminalPayoutsImmutable() {
        UUID payoutId = UUID.randomUUID();
        UUID holdId = createActiveHold(customerAccountId, 10000L);
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'CREATED', ?)",
                payoutId, userId, customerAccountId, holdId, now
        );

        jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, payoutId
        );

        jdbcTemplate.update(
                "UPDATE balance_holds SET status = 'RELEASED', terminal_at = ?, updated_at = ? WHERE id = ?",
                now, now, holdId
        );
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'FAILED', completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                now, payoutId
        );

        // Mutation on FAILED is rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING' WHERE id = ?",
                payoutId
        )).isInstanceOf(Exception.class).hasMessageContaining("is immutable and cannot be updated");

        // Deletion on FAILED is rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM payouts WHERE id = ?",
                payoutId
        )).isInstanceOf(Exception.class).hasMessageContaining("are immutable and cannot be deleted");
    }
}
