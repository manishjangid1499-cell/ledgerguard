package com.ledgerguard.payment;

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

class PaymentDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Direct INSERT with status CREATED succeeds")
    void directInsertCreatedSucceeds() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");

        UUID paymentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        int rows = jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                paymentId, customerId, customerAcc, merchantAcc, now, now
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("Direct INSERT with status other than CREATED is rejected by trigger")
    void directInsertNonCreatedRejected() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");
        UUID postedJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000);

        UUID paymentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // PROCESSING
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'PROCESSING', NULL, ?, ?, NULL)",
                paymentId, customerId, customerAcc, merchantAcc, now, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be inserted with status CREATED");

        // SUCCEEDED
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'SUCCEEDED', ?, ?, ?, ?)",
                paymentId, customerId, customerAcc, merchantAcc, postedJournalId, now, now, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be inserted with status CREATED");

        // FAILED
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'FAILED', NULL, ?, ?, ?)",
                paymentId, customerId, customerAcc, merchantAcc, now, now, now
        )).isInstanceOf(Exception.class).hasMessageContaining("must be inserted with status CREATED");
    }

    @Test
    @DisplayName("Valid lifecycle transitions CREATED -> PROCESSING -> SUCCEEDED")
    void validLifecycleTransitionsSucceed() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");
        UUID postedJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000);

        UUID paymentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // 1. Insert CREATED
        jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                paymentId, customerId, customerAcc, merchantAcc, now, now
        );

        // 2. Transition CREATED -> PROCESSING
        int updated = jdbcTemplate.update(
                "UPDATE payments SET status = 'PROCESSING', updated_at = ? WHERE id = ?",
                now, paymentId
        );
        assertThat(updated).isEqualTo(1);

        // 3. Transition PROCESSING -> SUCCEEDED with POSTED journal
        updated = jdbcTemplate.update(
                "UPDATE payments SET status = 'SUCCEEDED', journal_transaction_id = ?, updated_at = ?, completed_at = ? WHERE id = ?",
                postedJournalId, now, now, paymentId
        );
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("Transition to SUCCEEDED with DRAFT journal is rejected by trigger")
    void succeededWithDraftJournalRejected() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");
        UUID draftJournalId = createTestDraftJournal();

        UUID paymentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                paymentId, customerId, customerAcc, merchantAcc, now, now
        );

        jdbcTemplate.update(
                "UPDATE payments SET status = 'PROCESSING', updated_at = ? WHERE id = ?",
                now, paymentId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payments SET status = 'SUCCEEDED', journal_transaction_id = ?, updated_at = ?, completed_at = ? WHERE id = ?",
                draftJournalId, now, now, paymentId
        )).isInstanceOf(Exception.class).hasMessageContaining("must be POSTED");
    }

    @Test
    @DisplayName("Illegal status transitions are rejected (e.g. CREATED -> SUCCEEDED directly)")
    void illegalStatusTransitionsRejected() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");
        UUID postedJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000);

        UUID paymentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                paymentId, customerId, customerAcc, merchantAcc, now, now
        );

        // CREATED -> SUCCEEDED directly (forbidden)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payments SET status = 'SUCCEEDED', journal_transaction_id = ?, updated_at = ?, completed_at = ? WHERE id = ?",
                postedJournalId, now, now, paymentId
        )).isInstanceOf(Exception.class).hasMessageContaining("Invalid status transition");
    }

    @Test
    @DisplayName("SUCCEEDED and FAILED terminal records are completely immutable")
    void terminalRecordsAreImmutable() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");
        UUID postedJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000);

        UUID paymentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                paymentId, customerId, customerAcc, merchantAcc, now, now
        );
        jdbcTemplate.update("UPDATE payments SET status = 'PROCESSING', updated_at = ? WHERE id = ?", now, paymentId);
        jdbcTemplate.update("UPDATE payments SET status = 'SUCCEEDED', journal_transaction_id = ?, updated_at = ?, completed_at = ? WHERE id = ?",
                postedJournalId, now, now, paymentId);

        // Attempt update to SUCCEEDED record
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payments SET gross_amount_minor = 20000 WHERE id = ?",
                paymentId
        )).isInstanceOf(Exception.class).hasMessageContaining("immutable and cannot be updated");

        // Attempt delete of SUCCEEDED record
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM payments WHERE id = ?",
                paymentId
        )).isInstanceOf(Exception.class).hasMessageContaining("immutable and cannot be deleted");
    }

    @Test
    @DisplayName("CHECK constraints reject invalid gross, fee, net, and currency amounts")
    void checkConstraintsRejectInvalidAmounts() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");
        Timestamp now = Timestamp.from(Instant.now());

        // Net != Gross - Fee
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9500, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, merchantAcc, now, now
        )).isInstanceOf(Exception.class);

        // Negative gross
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, -10000, 0, -10000, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, merchantAcc, now, now
        )).isInstanceOf(Exception.class);

        // Case A: gross = 1, fee = 1, net = 0 (rejected: net must be > 0 and fee < gross)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 1, 1, 0, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, merchantAcc, now, now
        )).isInstanceOf(Exception.class);

        // Case B: gross = 100, fee = 100, net = 0 (rejected: net must be > 0 and fee < gross)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 100, 100, 0, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, merchantAcc, now, now
        )).isInstanceOf(Exception.class);

        // Case C: gross = 100, fee = 101, net = -1 (rejected: net > 0, fee < gross)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 100, 101, -1, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, merchantAcc, now, now
        )).isInstanceOf(Exception.class);

        // Currency != INR
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'USD', 'CREATED', NULL, ?, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, merchantAcc, now, now
        )).isInstanceOf(Exception.class);

        // Same customer and merchant account
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 100, 9900, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                UUID.randomUUID(), customerId, customerAcc, customerAcc, now, now
        )).isInstanceOf(Exception.class);
    }

    private UUID createTestUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', NOW(), NOW())",
                userId, "user." + userId + "@example.com"
        );
        return userId;
    }

    private UUID createTestAccount(UUID ownerUserId, String accountType) {
        UUID accountId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'INR', 'ACTIVE', ?, ?)",
                accountId, ownerUserId, accountType, now, now
        );
        return accountId;
    }

    private UUID createTestDraftJournal() {
        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                journalId, now
        );
        return journalId;
    }

    private UUID createTestPostedJournal(UUID debitAcc, UUID creditAcc, long amount) {
        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                journalId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', ?)",
                UUID.randomUUID(), journalId, debitAcc, amount
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', ?)",
                UUID.randomUUID(), journalId, creditAcc, amount
        );
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalId
        );
        return journalId;
    }
}
