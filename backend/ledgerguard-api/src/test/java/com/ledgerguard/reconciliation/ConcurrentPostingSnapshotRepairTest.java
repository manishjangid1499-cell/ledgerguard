package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.reconciliation.application.SnapshotAutoRepairService;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Concurrent posting during snapshot repair serialization")
class ConcurrentPostingSnapshotRepairTest extends AbstractIntegrationTest {

    @Autowired
    private SnapshotAutoRepairService autoRepairService;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private User opsUser;
    private LedgerAccount customerAccount;
    private LedgerAccount clearingAccount;

    @BeforeEach
    void setUp() {
        opsUser = userRepository.save(new User(UUID.randomUUID(), "ops." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
        User customer = userRepository.save(new User(UUID.randomUUID(), "cust." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        customerAccount = accountRepository.save(LedgerAccount.createCustomerAccount(customer.getId()));
        clearingAccount = accountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING));

        jdbc.update("INSERT INTO ledger_balance_snapshots (ledger_account_id, balance_minor, updated_at) VALUES (?, 0, NOW()) ON CONFLICT DO NOTHING", customerAccount.getId());
        jdbc.update("INSERT INTO ledger_balance_snapshots (ledger_account_id, balance_minor, updated_at) VALUES (?, 0, NOW()) ON CONFLICT DO NOTHING", clearingAccount.getId());
    }

    @Test
    @DisplayName("Snapshot repair locks snapshot row FOR UPDATE; concurrent posting blocks at V3 trigger, then applies delta without lost update")
    void concurrentPostingBlocksOnRepairSnapshotLock() throws Exception {
        // Initial posted balance: 100,000 minor
        postJournal(customerAccount.getId(), clearingAccount.getId(), 100000L);

        // Corrupt snapshot balance to 20,000
        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = 20000 WHERE ledger_account_id = ?", customerAccount.getId());

        UUID caseId = seedCase(customerAccount.getId(), ReconciliationProblemType.SNAPSHOT_MISMATCH);

        CountDownLatch repairLockedSnapshot = new CountDownLatch(1);
        CountDownLatch postingTriggered = new CountDownLatch(1);
        CountDownLatch repairCanCommit = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Thread 1: Repair locks snapshot FOR UPDATE, recomputes, and holds lock until posting begins
        Future<?> repairFuture = executor.submit(() -> {
            txTemplate.execute(status -> {
                // Step 1: Lock snapshot FOR UPDATE
                jdbc.queryForMap("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ? FOR UPDATE", customerAccount.getId());
                repairLockedSnapshot.countDown();

                // Wait for concurrent posting thread to launch and attempt lock acquisition
                try {
                    boolean started = postingTriggered.await(5, TimeUnit.SECONDS);
                    assertThat(started).isTrue();
                    // Short sleep to ensure concurrent posting thread has arrived at V3 trigger and blocked on lock
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Step 2: Reconstruct balance from committed journals (which sees 100,000)
                BigDecimal reconstructed = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor::NUMERIC ELSE -je.amount_minor::NUMERIC END), 0::NUMERIC) " +
                        "FROM journal_entries je JOIN journal_transactions jt ON jt.id = je.journal_transaction_id " +
                        "WHERE je.ledger_account_id = ? AND jt.status = 'POSTED'", BigDecimal.class, customerAccount.getId());

                // Step 3: Write repaired snapshot balance
                jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = ?, updated_at = NOW() WHERE ledger_account_id = ?",
                        reconstructed.longValue(), customerAccount.getId());

                // Resolve case
                jdbc.update("UPDATE reconciliation_cases SET status = 'RESOLVED', resolution_action = 'SNAPSHOT_REPAIRED', " +
                            "resolved_by_user_id = ?, resolved_at = NOW() WHERE id = ?", opsUser.getId(), caseId);

                return null;
            });
        });

        // Thread 2: Concurrent posting attempts to post 50,000
        Future<?> postingFuture = executor.submit(() -> {
            try {
                boolean locked = repairLockedSnapshot.await(5, TimeUnit.SECONDS);
                assertThat(locked).isTrue();

                // Signal that posting is being triggered
                postingTriggered.countDown();

                // This executes a journal post which enters V3 trigger and blocks on FOR UPDATE
                postJournal(customerAccount.getId(), clearingAccount.getId(), 50000L);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        repairFuture.get(10, TimeUnit.SECONDS);
        postingFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Final balance MUST equal initial 100,000 + concurrent 50,000 = 150,000
        Long finalBalance = jdbc.queryForObject("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?",
                Long.class, customerAccount.getId());
        assertThat(finalBalance).isEqualTo(150000L);

        // Case is RESOLVED
        var caseRow = jdbc.queryForMap("SELECT status, resolution_action FROM reconciliation_cases WHERE id = ?", caseId);
        assertThat(caseRow.get("status")).isEqualTo("RESOLVED");
        assertThat(caseRow.get("resolution_action")).isEqualTo("SNAPSHOT_REPAIRED");
    }

    private void postJournal(UUID customerAccId, UUID clearingAccId, long amountMinor) {
        ledgerPostingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(customerAccId, EntryDirection.CREDIT, Money.ofMinor(amountMinor, "INR")),
                        new PostingLine(clearingAccId, EntryDirection.DEBIT, Money.ofMinor(amountMinor, "INR"))
                )
        ));
    }

    private UUID seedCase(UUID entityId, ReconciliationProblemType problemType) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'SNAPSHOT_CONSISTENCY', ?, 'LEDGER_ACCOUNT', ?, 'mismatch', NOW())",
                itemId, runId, problemType.name(), entityId);

        return jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
    }
}
