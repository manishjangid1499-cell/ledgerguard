package com.ledgerguard.ledger;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalPostingAndImmutabilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Direct insertion of POSTED journal transaction is rejected by PostgreSQL trigger")
    void directPostInsertIsRejected() {
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), "POSTED", "INR", now, now
        )).hasMessageContaining("Direct insertion of status POSTED is forbidden");
    }

    @Test
    @DisplayName("Direct insertion of DRAFT journal transaction is allowed")
    void draftInsertIsAllowed() {
        Timestamp now = Timestamp.from(Instant.now());
        UUID id = UUID.randomUUID();
        int inserted = jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) VALUES (?, ?, ?, ?, ?)",
                id, "DRAFT", "INR", now, null
        );
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("Balanced double-entry journal transitions from DRAFT to POSTED successfully")
    void balancedJournalPostingSucceeds() {
        UUID sourceAccount = createSystemAccount("PLATFORM_RESERVE");
        UUID destAccount = createSystemAccount("PSP_CLEARING");
        UUID txnId = createDraftTransaction();

        insertEntry(txnId, sourceAccount, "DEBIT", 10000L);
        insertEntry(txnId, destAccount, "CREDIT", 10000L);

        Timestamp postedAt = Timestamp.from(Instant.now());
        int updated = jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                postedAt, txnId
        );

        assertThat(updated).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM journal_transactions WHERE id = ?",
                String.class, txnId
        );
        assertThat(status).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("Unbalanced double-entry journal posting is rejected by PostgreSQL trigger")
    void unbalancedJournalPostingIsRejected() {
        UUID sourceAccount = createSystemAccount("PLATFORM_RESERVE");
        UUID destAccount = createSystemAccount("PSP_CLEARING");
        UUID txnId = createDraftTransaction();

        insertEntry(txnId, sourceAccount, "DEBIT", 10000L);
        insertEntry(txnId, destAccount, "CREDIT", 9000L);

        Timestamp postedAt = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                postedAt, txnId
        )).hasMessageContaining("is not balanced");
    }

    @Test
    @DisplayName("Failed unbalanced posting rollback leaves journal in DRAFT state without partial state")
    void failedPostingRollbackLeavesJournalInDraft() {
        UUID sourceAccount = createSystemAccount("PLATFORM_RESERVE");
        UUID destAccount = createSystemAccount("PSP_CLEARING");
        UUID txnId = createDraftTransaction();

        insertEntry(txnId, sourceAccount, "DEBIT", 10000L);
        insertEntry(txnId, destAccount, "CREDIT", 8000L);

        Timestamp postedAt = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                postedAt, txnId
        )).hasMessageContaining("is not balanced");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM journal_transactions WHERE id = ?",
                String.class, txnId
        );
        Timestamp dbPostedAt = jdbcTemplate.queryForObject(
                "SELECT posted_at FROM journal_transactions WHERE id = ?",
                Timestamp.class, txnId
        );

        assertThat(status).isEqualTo("DRAFT");
        assertThat(dbPostedAt).isNull();
    }

    @Test
    @DisplayName("Single-sided journal transaction (only one debit) is rejected upon posting")
    void singleSidedPostingIsRejected() {
        UUID sourceAccount = createSystemAccount("PLATFORM_RESERVE");
        UUID txnId = createDraftTransaction();

        insertEntry(txnId, sourceAccount, "DEBIT", 10000L);

        Timestamp postedAt = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                postedAt, txnId
        )).hasMessageContaining("must contain at least 2 entries");
    }

    @Test
    @DisplayName("Two debits with zero credit entries is rejected upon posting")
    void twoDebitsWithNoCreditsIsRejected() {
        UUID account1 = createSystemAccount("PLATFORM_RESERVE");
        UUID account2 = createSystemAccount("PSP_CLEARING");
        UUID txnId = createDraftTransaction();

        insertEntry(txnId, account1, "DEBIT", 5000L);
        insertEntry(txnId, account2, "DEBIT", 5000L);

        Timestamp postedAt = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                postedAt, txnId
        )).hasMessageContaining("must contain at least one debit and one credit");
    }

    @Test
    @DisplayName("POSTED journal transaction is immutable against UPDATE")
    void postedTransactionCannotBeUpdated() {
        UUID txnId = createAndPostBalancedTransaction(10000L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_transactions SET currency = 'USD' WHERE id = ?",
                txnId
        )).hasMessageContaining("is immutable and cannot be updated");
    }

    @Test
    @DisplayName("POSTED journal transaction is immutable against DELETE")
    void postedTransactionCannotBeDeleted() {
        UUID txnId = createAndPostBalancedTransaction(10000L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM journal_transactions WHERE id = ?",
                txnId
        )).hasMessageContaining("is immutable and cannot be deleted");
    }

    @Test
    @DisplayName("POSTED journal entries are immutable against UPDATE")
    void postedEntriesCannotBeUpdated() {
        UUID txnId = createAndPostBalancedTransaction(10000L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_entries SET amount_minor = 20000 WHERE journal_transaction_id = ?",
                txnId
        )).hasMessageContaining("Cannot update entries of posted journal transaction");
    }

    @Test
    @DisplayName("POSTED journal entries are immutable against DELETE")
    void postedEntriesCannotBeDeleted() {
        UUID txnId = createAndPostBalancedTransaction(10000L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM journal_entries WHERE journal_transaction_id = ?",
                txnId
        )).hasMessageContaining("Cannot delete entries of posted journal transaction");
    }

    @Test
    @DisplayName("Inserting new entry into a POSTED journal transaction is rejected")
    void cannotAppendEntryToPostedTransaction() {
        UUID txnId = createAndPostBalancedTransaction(10000L);
        UUID account = createSystemAccount("PLATFORM_FEES");

        assertThatThrownBy(() -> insertEntry(txnId, account, "CREDIT", 500L))
                .hasMessageContaining("Cannot insert entries into posted journal transaction");
    }

    @Test
    @DisplayName("DRAFT journal entries can be modified prior to posting")
    void draftEntriesCanBeModified() {
        UUID sourceAccount = createSystemAccount("PLATFORM_RESERVE");
        UUID txnId = createDraftTransaction();
        UUID entryId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, ?, ?)",
                entryId, txnId, sourceAccount, "DEBIT", 5000L
        );

        int updated = jdbcTemplate.update(
                "UPDATE journal_entries SET amount_minor = 10000 WHERE id = ?",
                entryId
        );
        assertThat(updated).isEqualTo(1);

        int deleted = jdbcTemplate.update("DELETE FROM journal_entries WHERE id = ?", entryId);
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent entry append vs posting preserves money conservation invariants in PostgreSQL")
    void concurrentAppendVsPostingPreservesInvariants() throws Exception {
        UUID account1 = createSystemAccount("PLATFORM_RESERVE");
        UUID account2 = createSystemAccount("PSP_CLEARING");
        UUID account3 = createSystemAccount("PLATFORM_FEES");
        UUID txnId = createDraftTransaction();

        insertEntry(txnId, account1, "DEBIT", 10000L);
        insertEntry(txnId, account2, "CREDIT", 10000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        AtomicBoolean postingSucceeded = new AtomicBoolean(false);
        AtomicBoolean appendSucceeded = new AtomicBoolean(false);

        // Thread A: Attempts to post the transaction
        Future<?> postingFuture = executor.submit(() -> {
            try {
                startLatch.await();
                txTemplate.execute(status -> {
                    Timestamp postedAt = Timestamp.from(Instant.now());
                    jdbcTemplate.update(
                            "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                            postedAt, txnId
                    );
                    postingSucceeded.set(true);
                    return null;
                });
            } catch (Exception ignored) {
                // Could fail if append made the transaction unbalanced
            }
        });

        // Thread B: Concurrently attempts to append an entry (which would make it unbalanced if allowed)
        Future<?> appendFuture = executor.submit(() -> {
            try {
                startLatch.await();
                txTemplate.execute(status -> {
                    jdbcTemplate.update(
                            "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                                    "VALUES (?, ?, ?, ?, ?)",
                            UUID.randomUUID(), txnId, account3, "DEBIT", 5000L
                    );
                    appendSucceeded.set(true);
                    return null;
                });
            } catch (Exception ignored) {
                // Rejected if posting commits first
            }
        });

        startLatch.countDown();
        postingFuture.get(10, TimeUnit.SECONDS);
        appendFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Query final state in PostgreSQL
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM journal_transactions WHERE id = ?",
                String.class, txnId
        );

        Long debitSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM journal_entries WHERE journal_transaction_id = ? AND direction = 'DEBIT'",
                Long.class, txnId
        );
        Long creditSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM journal_entries WHERE journal_transaction_id = ? AND direction = 'CREDIT'",
                Long.class, txnId
        );

        if ("POSTED".equals(finalStatus)) {
            // Invariant: Any POSTED journal must be balanced. An unvalidated late append must NEVER exist in POSTED state.
            assertThat(debitSum).isEqualTo(creditSum);
            assertThat(appendSucceeded.get()).isFalse(); // Append must have been rejected because it would unbalance the journal
        } else {
            // If append succeeded first, posting was rejected due to imbalance, leaving journal in DRAFT
            assertThat(finalStatus).isEqualTo("DRAFT");
        }
    }

    private UUID createSystemAccount(String type) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, null, type, "INR", "ACTIVE", now, now
        );
        return id;
    }

    private UUID createDraftTransaction() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                id, "DRAFT", "INR", now, null
        );
        return id;
    }

    private void insertEntry(UUID txnId, UUID accountId, String direction, long amountMinor) {
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), txnId, accountId, direction, amountMinor
        );
    }

    private UUID createAndPostBalancedTransaction(long amountMinor) {
        UUID account1 = createSystemAccount("PLATFORM_RESERVE");
        UUID account2 = createSystemAccount("PSP_CLEARING");
        UUID txnId = createDraftTransaction();

        insertEntry(txnId, account1, "DEBIT", amountMinor);
        insertEntry(txnId, account2, "CREDIT", amountMinor);

        Timestamp postedAt = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                postedAt, txnId
        );
        return txnId;
    }
}
