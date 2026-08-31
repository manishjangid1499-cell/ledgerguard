package com.ledgerguard.ledger.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.domain.Wallet;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceSnapshotIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private WalletQueryService walletQueryService;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("CREDIT-normal accounts increase on CREDIT and decrease on DEBIT")
    void creditNormalBalanceCalculation() {
        UUID customerUser = createTestUser();
        LedgerAccount customerAccount = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.createCustomerAccount(customerUser)
        );
        LedgerAccount reserveAccount = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount feesAccount = createSystemAccount(AccountType.PLATFORM_FEES);

        // 1. Initial balance is 0
        assertThat(getSnapshotBalance(customerAccount.getId())).isEqualTo(0L);

        // 2. Fund customer: Reserve DEBIT 10000, Customer CREDIT 10000
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserveAccount.getId(), 10000L),
                PostingLine.credit(customerAccount.getId(), 10000L)
        ));
        assertThat(getSnapshotBalance(customerAccount.getId())).isEqualTo(10000L);
        assertThat(getSnapshotBalance(reserveAccount.getId())).isEqualTo(10000L); // DEBIT-normal increases on DEBIT

        // 3. Charge fee: Customer DEBIT 3000, Fees CREDIT 3000
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(customerAccount.getId(), 3000L),
                PostingLine.credit(feesAccount.getId(), 3000L)
        ));
        assertThat(getSnapshotBalance(customerAccount.getId())).isEqualTo(7000L);
        assertThat(getSnapshotBalance(feesAccount.getId())).isEqualTo(3000L);

        // 4. WalletQueryService reflects exact snapshot
        Wallet wallet = walletQueryService.findWalletByUserId(customerUser).orElseThrow();
        assertThat(wallet.balance().getMinorUnits()).isEqualTo(7000L);
    }

    @Test
    @DisplayName("DEBIT-normal accounts increase on DEBIT and decrease on CREDIT")
    void debitNormalBalanceCalculation() {
        LedgerAccount clearingAccount = createSystemAccount(AccountType.PSP_CLEARING);
        UUID customerUser = createTestUser();
        LedgerAccount customerAccount = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.createCustomerAccount(customerUser)
        );

        // 1. Inward funds: PSP_CLEARING DEBIT 10000, Customer CREDIT 10000
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(clearingAccount.getId(), 10000L),
                PostingLine.credit(customerAccount.getId(), 10000L)
        ));
        assertThat(getSnapshotBalance(clearingAccount.getId())).isEqualTo(10000L);

        // 2. Outward payout: Customer DEBIT 3000, PSP_CLEARING CREDIT 3000
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(customerAccount.getId(), 3000L),
                PostingLine.credit(clearingAccount.getId(), 3000L)
        ));
        assertThat(getSnapshotBalance(clearingAccount.getId())).isEqualTo(7000L);
    }

    @Test
    @DisplayName("Multi-line journal with multiple entries for same account aggregates delta correctly")
    void multiLineSameAccountAggregation() {
        UUID customerUser = createTestUser();
        LedgerAccount customerAccount = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.createCustomerAccount(customerUser)
        );
        LedgerAccount reserveAccount = createSystemAccount(AccountType.PLATFORM_RESERVE);

        // Customer receives CREDIT 6000 + CREDIT 4000 against Reserve DEBIT 10000
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserveAccount.getId(), 10000L),
                PostingLine.credit(customerAccount.getId(), 6000L),
                PostingLine.credit(customerAccount.getId(), 4000L)
        ));

        assertThat(getSnapshotBalance(customerAccount.getId())).isEqualTo(10000L);
        assertThat(reconstructBalance(customerAccount.getId(), AccountType.CUSTOMER)).isEqualTo(10000L);
    }

    @Test
    @DisplayName("DRAFT journal transactions do NOT modify balance snapshots")
    void draftJournalDoesNotAffectSnapshot() {
        UUID customerUser = createTestUser();
        LedgerAccount customerAccount = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.createCustomerAccount(customerUser)
        );
        LedgerAccount reserveAccount = createSystemAccount(AccountType.PLATFORM_RESERVE);

        UUID draftId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Insert DRAFT journal and entries directly via JDBC
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                draftId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', 5000)",
                UUID.randomUUID(), draftId, customerAccount.getId()
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', 5000)",
                UUID.randomUUID(), draftId, reserveAccount.getId()
        );

        // Snapshot MUST remain 0 because journal is still DRAFT
        assertThat(getSnapshotBalance(customerAccount.getId())).isEqualTo(0L);
        assertThat(getSnapshotBalance(reserveAccount.getId())).isEqualTo(0L);
    }

    @Test
    @DisplayName("Direct DB posting triggers automatic snapshot update via PostgreSQL trigger")
    void directDbPostingUpdatesSnapshot() {
        UUID customerUser = createTestUser();
        LedgerAccount customerAccount = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.createCustomerAccount(customerUser)
        );
        LedgerAccount reserveAccount = createSystemAccount(AccountType.PLATFORM_RESERVE);

        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                journalId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', 8000)",
                UUID.randomUUID(), journalId, customerAccount.getId()
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', 8000)",
                UUID.randomUUID(), journalId, reserveAccount.getId()
        );

        // Transition to POSTED directly in DB
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalId
        );

        // Snapshot is automatically updated by AFTER UPDATE trigger
        assertThat(getSnapshotBalance(customerAccount.getId())).isEqualTo(8000L);
        assertThat(getSnapshotBalance(reserveAccount.getId())).isEqualTo(8000L);
    }

    @Test
    @DisplayName("Snapshot arithmetic overflow rolls back entire posting transaction")
    void snapshotOverflowRollsBackPosting() {
        UUID customerUser = createTestUser();
        LedgerAccount customerAccount = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.createCustomerAccount(customerUser)
        );
        LedgerAccount reserveAccount = createSystemAccount(AccountType.PLATFORM_RESERVE);

        // Set snapshot balance near Long.MAX_VALUE
        long nearMax = Long.MAX_VALUE - 100L;
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = ? WHERE ledger_account_id = ?",
                nearMax, customerAccount.getId()
        );

        long journalCountBefore = journalTransactionRepository.count();

        // Attempt posting that adds 200 (exceeding Long.MAX_VALUE)
        PostJournalCommand overflowCommand = PostJournalCommand.of(
                PostingLine.debit(reserveAccount.getId(), 200L),
                PostingLine.credit(customerAccount.getId(), 200L)
        );

        assertThatThrownBy(() -> ledgerPostingService.post(overflowCommand))
                .isInstanceOf(Exception.class);

        // Verify transaction rolled back cleanly: no new journal, snapshot unchanged
        assertThat(journalTransactionRepository.count()).isEqualTo(journalCountBefore);
        assertThat(getSnapshotBalance(customerAccount.getId())).isEqualTo(nearMax);
    }

    @Test
    @DisplayName("Concurrent postings on shared accounts produce no lost updates or deadlocks")
    void concurrentSnapshotUpdatesNoLostUpdates() throws Exception {
        UUID customerUser = createTestUser();
        LedgerAccount customerAccount = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.createCustomerAccount(customerUser)
        );
        LedgerAccount reserveAccount = createSystemAccount(AccountType.PLATFORM_RESERVE);

        int threadCount = 10;
        long amountPerThread = 500L;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<PostingResult>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> ledgerPostingService.post(PostJournalCommand.of(
                    PostingLine.debit(reserveAccount.getId(), amountPerThread),
                    PostingLine.credit(customerAccount.getId(), amountPerThread)
            )));
        }

        List<Future<PostingResult>> futures = executor.invokeAll(tasks);
        for (Future<PostingResult> future : futures) {
            future.get(); // Ensure all completed successfully
        }
        executor.shutdown();

        long expectedTotal = threadCount * amountPerThread;
        assertThat(getSnapshotBalance(customerAccount.getId())).isEqualTo(expectedTotal);
        assertThat(getSnapshotBalance(reserveAccount.getId())).isEqualTo(expectedTotal);

        // Authoritative journal reconstruction matches maintained snapshot
        assertThat(reconstructBalance(customerAccount.getId(), AccountType.CUSTOMER)).isEqualTo(expectedTotal);
        assertThat(reconstructBalance(reserveAccount.getId(), AccountType.PLATFORM_RESERVE)).isEqualTo(expectedTotal);
    }

    private long getSnapshotBalance(UUID ledgerAccountId) {
        return ledgerBalanceSnapshotRepository.findById(ledgerAccountId)
                .map(LedgerBalanceSnapshot::getBalanceMinor)
                .orElseThrow();
    }

    private long reconstructBalance(UUID accountId, AccountType type) {
        String sql = """
            SELECT COALESCE(SUM(
                CASE
                    WHEN ? IN ('CUSTOMER', 'MERCHANT', 'PLATFORM_FEES') THEN
                        CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor ELSE -je.amount_minor END
                    ELSE
                        CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor ELSE -je.amount_minor END
                END
            ), 0)
            FROM journal_entries je
            JOIN journal_transactions jt ON jt.id = je.journal_transaction_id
            WHERE je.ledger_account_id = ? AND jt.status = 'POSTED'
        """;
        return jdbcTemplate.queryForObject(sql, Long.class, type.name(), accountId);
    }

    private LedgerAccount createSystemAccount(AccountType type) {
        LedgerAccount account = LedgerAccount.createSystemAccount(type);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private UUID createTestUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, "snapshot_test." + id + "@example.com", "$2a$10$dummyHashValueForTestingPurposeOnly", "CUSTOMER", "ACTIVE", now, now
        );
        return id;
    }
}
