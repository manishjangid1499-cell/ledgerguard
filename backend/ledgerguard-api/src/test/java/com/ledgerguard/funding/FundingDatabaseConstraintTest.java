package com.ledgerguard.funding;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FundingDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Direct INSERT with status PROCESSING succeeds for active customer account")
    void directInsertProcessingSucceeds() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER", "ACTIVE", "INR");

        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        int rows = jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                fundingId, customerId, customerAcc, now
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("Direct INSERT with status SUCCEEDED is rejected by trigger")
    void directInsertSucceededRejected() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER", "ACTIVE", "INR");
        UUID clearingAcc = createTestAccount(null, "PSP_CLEARING", "ACTIVE", "INR");
        UUID journalId = createTestFundingJournal(clearingAcc, customerAcc, 10000);

        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'SUCCEEDED', ?, ?, ?, ?)",
                fundingId, customerId, customerAcc, providerOpId, journalId, now, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be inserted with status PROCESSING");
    }

    @Test
    @DisplayName("Direct INSERT with MERCHANT or PSP_CLEARING as customer account is rejected")
    void directInsertWrongAccountTypeRejected() {
        UUID userId = createTestUser();
        UUID merchantAcc = createTestAccount(userId, "MERCHANT", "ACTIVE", "INR");
        UUID clearingAcc = createTestAccount(null, "PSP_CLEARING", "ACTIVE", "INR");

        UUID fundingId1 = UUID.randomUUID();
        UUID fundingId2 = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                fundingId1, userId, merchantAcc, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be of type CUSTOMER");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                fundingId2, userId, clearingAcc, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be of type CUSTOMER");
    }

    @Test
    @DisplayName("Direct INSERT with owner mismatch is rejected")
    void directInsertOwnerMismatchRejected() {
        UUID user1 = createTestUser();
        UUID user2 = createTestUser();
        UUID customerAcc = createTestAccount(user1, "CUSTOMER", "ACTIVE", "INR");

        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                fundingId, user2, customerAcc, now
        )).isInstanceOf(Exception.class).hasMessageContaining("does not match initiator");
    }

    @Test
    @DisplayName("Direct INSERT with CLOSED customer account is rejected")
    void directInsertClosedAccountRejected() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER", "CLOSED", "INR");

        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                fundingId, customerId, customerAcc, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be ACTIVE");
    }

    @Test
    @DisplayName("Direct INSERT with amount <= 0 is rejected by check constraint")
    void directInsertInvalidAmountRejected() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER", "ACTIVE", "INR");

        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 0, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, now
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, -100, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, now
        )).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Valid transition PROCESSING -> SUCCEEDED with valid posted settlement journal succeeds")
    void validSettlementTransitionSucceeds() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER", "ACTIVE", "INR");
        UUID clearingAcc = createTestAccount(null, "PSP_CLEARING", "ACTIVE", "INR");
        UUID journalId = createTestFundingJournal(clearingAcc, customerAcc, 10000);

        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // 1. Insert PROCESSING
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                fundingId, customerId, customerAcc, now
        );

        // 2. Transition to SUCCEEDED
        int updated = jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'SUCCEEDED', provider_operation_id = ?, journal_transaction_id = ?, completed_at = ? WHERE id = ?",
                providerOpId, journalId, now, fundingId
        );
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("Transition to SUCCEEDED with wrong journal amount or accounts is rejected")
    void transitionWrongJournalRejected() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER", "ACTIVE", "INR");
        UUID clearingAcc = createTestAccount(null, "PSP_CLEARING", "ACTIVE", "INR");
        UUID otherCustomer = createTestUser();
        UUID otherCustomerAcc = createTestAccount(otherCustomer, "CUSTOMER", "ACTIVE", "INR");

        // Journal with wrong amount (5000 instead of 10000)
        UUID wrongAmountJournal = createTestFundingJournal(clearingAcc, customerAcc, 5000);

        // Journal with wrong credit account (other customer)
        UUID wrongAccountJournal = createTestFundingJournal(clearingAcc, otherCustomerAcc, 10000);

        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                fundingId, customerId, customerAcc, now
        );

        // Wrong amount
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'SUCCEEDED', provider_operation_id = ?, journal_transaction_id = ?, completed_at = ? WHERE id = ?",
                providerOpId, wrongAmountJournal, now, fundingId
        )).isInstanceOf(Exception.class).hasMessageContaining("do not match funding amount");

        // Wrong credit account
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'SUCCEEDED', provider_operation_id = ?, journal_transaction_id = ?, completed_at = ? WHERE id = ?",
                providerOpId, wrongAccountJournal, now, fundingId
        )).isInstanceOf(Exception.class).hasMessageContaining("does not match funding customer account");
    }

    @Test
    @DisplayName("After SUCCEEDED, mutation and deletion are rejected")
    void succeededFundingIsImmutable() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER", "ACTIVE", "INR");
        UUID clearingAcc = createTestAccount(null, "PSP_CLEARING", "ACTIVE", "INR");
        UUID journalId = createTestFundingJournal(clearingAcc, customerAcc, 10000);

        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'PROCESSING', NULL, NULL, ?, NULL)",
                fundingId, customerId, customerAcc, now
        );

        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'SUCCEEDED', provider_operation_id = ?, journal_transaction_id = ?, completed_at = ? WHERE id = ?",
                providerOpId, journalId, now, fundingId
        );

        // Attempt amount mutation
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET amount_minor = 20000 WHERE id = ?",
                fundingId
        )).isInstanceOf(Exception.class).hasMessageContaining("is immutable and cannot be updated");

        // Attempt delete
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM funding_operations WHERE id = ?",
                fundingId
        )).isInstanceOf(Exception.class).hasMessageContaining("is immutable and cannot be deleted");
    }

    private UUID createTestUser() {
        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                userId, "user-" + userId + "@example.com", now, now
        );
        return userId;
    }

    private UUID createTestAccount(UUID ownerUserId, String accountType, String status, String currency) {
        UUID accId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                accId, ownerUserId, accountType, currency, status, now, now
        );
        return accId;
    }

    private UUID createTestFundingJournal(UUID debitClearingAcc, UUID creditCustomerAcc, long amountMinor) {
        UUID txnId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) VALUES (?, 'DRAFT', 'INR', ?, NULL)",
                txnId, now
        );

        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'DEBIT', ?)",
                UUID.randomUUID(), txnId, debitClearingAcc, amountMinor
        );

        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'CREDIT', ?)",
                UUID.randomUUID(), txnId, creditCustomerAcc, amountMinor
        );

        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, txnId
        );

        return txnId;
    }
}
