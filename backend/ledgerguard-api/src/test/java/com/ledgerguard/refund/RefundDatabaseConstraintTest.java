package com.ledgerguard.refund;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Direct INSERT into refunds succeeds with valid POSTED journal and SUCCEEDED payment")
    void validRefundInsertSucceeds() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");

        UUID paymentJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000L);
        UUID paymentId = createTestPayment(customerId, customerAcc, merchantAcc, 10000L, 100L, 9900L, "SUCCEEDED", paymentJournalId);

        UUID refundJournalId = createTestPostedJournal(merchantAcc, customerAcc, 2500L);
        UUID refundId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        int rows = jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 2500, 2475, 25, 'INR', ?, ?)",
                refundId, paymentId, merchantId, refundJournalId, now
        );
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("Direct INSERT into refunds is rejected if journal is DRAFT")
    void refundWithDraftJournalFails() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");

        UUID paymentJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000L);
        UUID paymentId = createTestPayment(customerId, customerAcc, merchantAcc, 10000L, 100L, 9900L, "SUCCEEDED", paymentJournalId);

        UUID draftRefundJournalId = createTestDraftJournal();
        UUID refundId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 2500, 2475, 25, 'INR', ?, ?)",
                refundId, paymentId, merchantId, draftRefundJournalId, now
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Refund must reference a POSTED journal transaction");
    }

    @Test
    @DisplayName("Direct INSERT into refunds is rejected if payment is not SUCCEEDED")
    void refundWithNonSucceededPaymentFails() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");

        UUID paymentId = createTestPayment(customerId, customerAcc, merchantAcc, 10000L, 100L, 9900L, "CREATED", null);

        UUID refundJournalId = createTestPostedJournal(merchantAcc, customerAcc, 2500L);
        UUID refundId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 2500, 2475, 25, 'INR', ?, ?)",
                refundId, paymentId, merchantId, refundJournalId, now
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Refund can only be created for SUCCEEDED payments");
    }

    @Test
    @DisplayName("Direct INSERT into refunds is rejected if cumulative cap is exceeded")
    void cumulativeRefundCapRejectedByTrigger() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");

        UUID paymentJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000L);
        UUID paymentId = createTestPayment(customerId, customerAcc, merchantAcc, 10000L, 100L, 9900L, "SUCCEEDED", paymentJournalId);

        UUID r1Journal = createTestPostedJournal(merchantAcc, customerAcc, 7000L);
        Timestamp now = Timestamp.from(Instant.now());

        // First refund: 7000 -> success
        jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 7000, 6930, 70, 'INR', ?, ?)",
                UUID.randomUUID(), paymentId, merchantId, r1Journal, now
        );

        // Second refund: 4000 (total 11000 > 10000) -> rejected by trigger
        UUID r2Journal = createTestPostedJournal(merchantAcc, customerAcc, 4000L);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 4000, 3960, 40, 'INR', ?, ?)",
                UUID.randomUUID(), paymentId, merchantId, r2Journal, now
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("exceeds payment gross amount");
    }

    @Test
    @DisplayName("Direct concurrent JDBC inserts serialize via parent payment lock and enforce cumulative cap")
    void concurrentDirectJdbcCapEnforcement() throws Exception {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");

        UUID paymentJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000L);
        UUID paymentId = createTestPayment(customerId, customerAcc, merchantAcc, 10000L, 100L, 9900L, "SUCCEEDED", paymentJournalId);

        UUID j1 = createTestPostedJournal(merchantAcc, customerAcc, 7000L);
        UUID j2 = createTestPostedJournal(merchantAcc, customerAcc, 7000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        Runnable task1 = () -> {
            try {
                startLatch.await();
                try (Connection conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                                    "VALUES (?, ?, ?, 7000, 6930, 70, 'INR', ?, NOW())")) {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, paymentId);
                        ps.setObject(3, merchantId);
                        ps.setObject(4, j1);
                        ps.executeUpdate();
                        conn.commit();
                        successes.incrementAndGet();
                    } catch (Exception e) {
                        conn.rollback();
                        failures.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        };

        Runnable task2 = () -> {
            try {
                startLatch.await();
                try (Connection conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                                    "VALUES (?, ?, ?, 7000, 6930, 70, 'INR', ?, NOW())")) {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, paymentId);
                        ps.setObject(3, merchantId);
                        ps.setObject(4, j2);
                        ps.executeUpdate();
                        conn.commit();
                        successes.incrementAndGet();
                    } catch (Exception e) {
                        conn.rollback();
                        failures.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        };

        executor.submit(task1);
        executor.submit(task2);

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);

        Long totalRefunded = jdbcTemplate.queryForObject(
                "SELECT SUM(refund_amount_minor) FROM refunds WHERE payment_id = ?",
                Long.class,
                paymentId
        );
        assertThat(totalRefunded).isEqualTo(7000L);
    }

    @Test
    @DisplayName("Database trigger rejects UPDATE and DELETE on refunds table")
    void immutabilityEnforced() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");

        UUID paymentJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000L);
        UUID paymentId = createTestPayment(customerId, customerAcc, merchantAcc, 10000L, 100L, 9900L, "SUCCEEDED", paymentJournalId);

        UUID refundJournalId = createTestPostedJournal(merchantAcc, customerAcc, 2500L);
        UUID refundId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 2500, 2475, 25, 'INR', ?, ?)",
                refundId, paymentId, merchantId, refundJournalId, now
        );

        // UPDATE refund_amount_minor -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE refunds SET refund_amount_minor = 3000 WHERE id = ?",
                refundId
        )).isInstanceOf(Exception.class).hasMessageContaining("Refunds are immutable");

        // UPDATE merchant_debit_amount_minor -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE refunds SET merchant_debit_amount_minor = 3000 WHERE id = ?",
                refundId
        )).isInstanceOf(Exception.class).hasMessageContaining("Refunds are immutable");

        // UPDATE fee_debit_amount_minor -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE refunds SET fee_debit_amount_minor = 30 WHERE id = ?",
                refundId
        )).isInstanceOf(Exception.class).hasMessageContaining("Refunds are immutable");

        // UPDATE payment_id -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE refunds SET payment_id = ? WHERE id = ?",
                UUID.randomUUID(), refundId
        )).isInstanceOf(Exception.class).hasMessageContaining("Refunds are immutable");

        // UPDATE journal_transaction_id -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE refunds SET journal_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), refundId
        )).isInstanceOf(Exception.class).hasMessageContaining("Refunds are immutable");

        // DELETE -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM refunds WHERE id = ?",
                refundId
        )).isInstanceOf(Exception.class).hasMessageContaining("Refunds are immutable");
    }

    @Test
    @DisplayName("Amount and balance check constraints are enforced on refunds table")
    void amountCheckConstraints() {
        UUID customerId = createTestUser();
        UUID customerAcc = createTestAccount(customerId, "CUSTOMER");
        UUID merchantId = createTestUser();
        UUID merchantAcc = createTestAccount(merchantId, "MERCHANT");

        UUID paymentJournalId = createTestPostedJournal(customerAcc, merchantAcc, 10000L);
        UUID paymentId = createTestPayment(customerId, customerAcc, merchantAcc, 10000L, 100L, 9900L, "SUCCEEDED", paymentJournalId);
        UUID refundJournalId = createTestPostedJournal(merchantAcc, customerAcc, 100L);
        Timestamp now = Timestamp.from(Instant.now());

        // refund_amount_minor <= 0
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 0, 0, 0, 'INR', ?, ?)",
                UUID.randomUUID(), paymentId, merchantId, refundJournalId, now
        )).isInstanceOf(Exception.class);

        // merchant_debit < 0
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 100, -10, 110, 'INR', ?, ?)",
                UUID.randomUUID(), paymentId, merchantId, refundJournalId, now
        )).isInstanceOf(Exception.class);

        // fee_debit < 0
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 100, 110, -10, 'INR', ?, ?)",
                UUID.randomUUID(), paymentId, merchantId, refundJournalId, now
        )).isInstanceOf(Exception.class);

        // refund != merchant + fee
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 100, 50, 40, 'INR', ?, ?)",
                UUID.randomUUID(), paymentId, merchantId, refundJournalId, now
        )).isInstanceOf(Exception.class);

        // currency != INR
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO refunds (id, payment_id, initiated_by_user_id, refund_amount_minor, merchant_debit_amount_minor, fee_debit_amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, 100, 99, 1, 'USD', ?, ?)",
                UUID.randomUUID(), paymentId, merchantId, refundJournalId, now
        )).isInstanceOf(Exception.class);
    }

    private UUID createTestUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'MERCHANT', 'ACTIVE', NOW(), NOW())",
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

    private UUID createTestPayment(UUID customerUserId, UUID customerLedgerAccountId, UUID merchantLedgerAccountId,
                                   long gross, long fee, long net, String status, UUID journalId) {
        UUID paymentId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO payments (id, customer_user_id, customer_ledger_account_id, merchant_ledger_account_id, gross_amount_minor, fee_amount_minor, merchant_net_amount_minor, currency, status, journal_transaction_id, created_at, updated_at, completed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'INR', 'CREATED', NULL, ?, ?, NULL)",
                paymentId, customerUserId, customerLedgerAccountId, merchantLedgerAccountId, gross, fee, net, now, now
        );

        if ("PROCESSING".equals(status) || "SUCCEEDED".equals(status) || "FAILED".equals(status)) {
            jdbcTemplate.update("UPDATE payments SET status = 'PROCESSING', updated_at = ? WHERE id = ?", now, paymentId);
        }

        if ("SUCCEEDED".equals(status)) {
            jdbcTemplate.update("UPDATE payments SET status = 'SUCCEEDED', journal_transaction_id = ?, updated_at = ?, completed_at = ? WHERE id = ?",
                    journalId, now, now, paymentId);
        } else if ("FAILED".equals(status)) {
            jdbcTemplate.update("UPDATE payments SET status = 'FAILED', updated_at = ?, completed_at = ? WHERE id = ?",
                    now, now, paymentId);
        }

        return paymentId;
    }
}
