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
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.reconciliation.api.SnapshotRepairResponse;
import com.ledgerguard.reconciliation.application.SnapshotAutoRepairService;
import com.ledgerguard.reconciliation.application.SnapshotConsistencyChecker;
import com.ledgerguard.reconciliation.domain.ReconciliationCase;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationConflictException;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationResolutionAction;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationCaseRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Snapshot auto-repair, stale-item protection, and concurrency")
class SnapshotAutoRepairTest extends AbstractIntegrationTest {

    @Autowired
    private SnapshotAutoRepairService autoRepairService;

    @Autowired
    private SnapshotConsistencyChecker snapshotConsistencyChecker;

    @Autowired
    private ReconciliationCaseRepository caseRepository;

    @Autowired
    private ReconciliationItemRepository itemRepository;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository snapshotRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private User opsA;
    private User opsB;
    private LedgerAccount customerAccount;
    private LedgerAccount clearingAccount;

    @BeforeEach
    void setUp() {
        opsA = userRepository.save(new User(UUID.randomUUID(), "opsA." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
        opsB = userRepository.save(new User(UUID.randomUUID(), "opsB." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));

        User customer = userRepository.save(new User(UUID.randomUUID(), "cust." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        customerAccount = accountRepository.save(LedgerAccount.createCustomerAccount(customer.getId()));
        clearingAccount = accountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING));

        jdbc.update("INSERT INTO ledger_balance_snapshots (ledger_account_id, balance_minor, updated_at) VALUES (?, 0, NOW()) ON CONFLICT DO NOTHING", customerAccount.getId());
        jdbc.update("INSERT INTO ledger_balance_snapshots (ledger_account_id, balance_minor, updated_at) VALUES (?, 0, NOW()) ON CONFLICT DO NOTHING", clearingAccount.getId());
    }

    @Test
    @DisplayName("Auto-repair successfully restores corrupted snapshot balance from immutable POSTED journals")
    void snapshotRepairSuccess() {
        // Post 500.00 INR (50,000 minor) credit to customer
        postJournal(customerAccount.getId(), clearingAccount.getId(), 50000L);

        // Corrupt customer snapshot balance to 10,000
        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = 10000 WHERE ledger_account_id = ?", customerAccount.getId());

        // Create discrepancy item & case
        UUID caseId = seedCase(customerAccount.getId(), ReconciliationProblemType.SNAPSHOT_MISMATCH);

        SnapshotRepairResponse response = autoRepairService.repairSnapshot(caseId, opsA.getId());

        assertThat(response.previousBalanceMinor()).isEqualTo("10000");
        assertThat(response.repairedBalanceMinor()).isEqualTo("50000");
        assertThat(response.resolutionAction()).isEqualTo("SNAPSHOT_REPAIRED");

        // Verify DB snapshot
        Long balance = jdbc.queryForObject("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, customerAccount.getId());
        assertThat(balance).isEqualTo(50000L);

        // Verify case is RESOLVED
        ReconciliationCase reconCase = caseRepository.findById(caseId).orElseThrow();
        assertThat(reconCase.getStatus()).isEqualTo(ReconciliationCaseStatus.RESOLVED);
        assertThat(reconCase.getResolutionAction()).isEqualTo(ReconciliationResolutionAction.SNAPSHOT_REPAIRED);
        assertThat(reconCase.getResolvedByUserId()).isEqualTo(opsA.getId());

        // Subsequent Level 2 check produces 0 discrepancies for this account
        UUID runId = insertRunningRun();
        snapshotConsistencyChecker.check(runId);
        long itemCouunt = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_items WHERE reconciliation_run_id = ? AND entity_id = ?",
                Long.class, runId, customerAccount.getId());
        assertThat(itemCouunt).isEqualTo(0L);
    }

    @Test
    @DisplayName("Stale item protection: repair dynamically computes latest journal truth rather than stale item value")
    void staleItemProtection() {
        // Initial state: 50,000 posted
        postJournal(customerAccount.getId(), clearingAccount.getId(), 50000L);

        // Corrupt snapshot to 10,000
        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = 10000 WHERE ledger_account_id = ?", customerAccount.getId());

        // Seed case with stale expected_value 50,000
        UUID caseId = seedCase(customerAccount.getId(), ReconciliationProblemType.SNAPSHOT_MISMATCH);

        // New legitimate transaction occurs AFTER case was opened, adding 25,000
        postJournal(customerAccount.getId(), clearingAccount.getId(), 25000L);
        // At this point, snapshot has 10,000 + 25,000 = 35,000, but true total is 75,000

        // Repair executed: MUST recompute 75,000, NOT the old 50,000
        SnapshotRepairResponse response = autoRepairService.repairSnapshot(caseId, opsA.getId());

        assertThat(response.repairedBalanceMinor()).isEqualTo("75000");
        Long balance = jdbc.queryForObject("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, customerAccount.getId());
        assertThat(balance).isEqualTo(75000L);
    }

    @Test
    @DisplayName("Already consistent: if snapshot already equals journal truth at repair time, no write occurs")
    void alreadyConsistentHandling() {
        postJournal(customerAccount.getId(), clearingAccount.getId(), 40000L);

        // Snapshot is correct (40,000)
        UUID caseId = seedCase(customerAccount.getId(), ReconciliationProblemType.SNAPSHOT_MISMATCH);

        SnapshotRepairResponse response = autoRepairService.repairSnapshot(caseId, opsA.getId());

        assertThat(response.previousBalanceMinor()).isEqualTo("40000");
        assertThat(response.repairedBalanceMinor()).isEqualTo("40000");
        assertThat(response.resolutionAction()).isEqualTo("ALREADY_CONSISTENT");

        ReconciliationCase reconCase = caseRepository.findById(caseId).orElseThrow();
        assertThat(reconCase.getStatus()).isEqualTo(ReconciliationCaseStatus.RESOLVED);
        assertThat(reconCase.getResolutionAction()).isEqualTo(ReconciliationResolutionAction.ALREADY_CONSISTENT);
    }

    @Test
    @DisplayName("Repair idempotency: replaying repair on resolved case returns existing result")
    void repairIdempotency() {
        postJournal(customerAccount.getId(), clearingAccount.getId(), 30000L);
        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = 5000 WHERE ledger_account_id = ?", customerAccount.getId());
        UUID caseId = seedCase(customerAccount.getId(), ReconciliationProblemType.SNAPSHOT_MISMATCH);

        autoRepairService.repairSnapshot(caseId, opsA.getId());
        SnapshotRepairResponse replay = autoRepairService.repairSnapshot(caseId, opsA.getId());

        assertThat(replay.repairedBalanceMinor()).isEqualTo("30000");
        assertThat(replay.resolutionAction()).isEqualTo("SNAPSHOT_REPAIRED");
    }

    @Test
    @DisplayName("Ineligible problem types throw 409 Conflict on repair attempt")
    void ineligibleProblemTypesThrowConflict() {
        UUID caseMissing = seedCase(customerAccount.getId(), ReconciliationProblemType.SNAPSHOT_MISSING);
        assertThatThrownBy(() -> autoRepairService.repairSnapshot(caseMissing, opsA.getId()))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("not eligible for snapshot auto-repair");

        UUID caseJournal = seedCase(UUID.randomUUID(), ReconciliationProblemType.UNBALANCED_JOURNAL);
        assertThatThrownBy(() -> autoRepairService.repairSnapshot(caseJournal, opsA.getId()))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("not eligible for snapshot auto-repair");
    }

    @Test
    @DisplayName("Disappeared snapshot row returns 409 Conflict without auto-creating snapshot row")
    void disappearedSnapshotRowReturnsConflict() {
        postJournal(customerAccount.getId(), clearingAccount.getId(), 20000L);
        UUID caseId = seedCase(customerAccount.getId(), ReconciliationProblemType.SNAPSHOT_MISMATCH);

        // Delete snapshot row
        jdbc.update("DELETE FROM ledger_balance_snapshots WHERE ledger_account_id = ?", customerAccount.getId());

        assertThatThrownBy(() -> autoRepairService.repairSnapshot(caseId, opsA.getId()))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("Snapshot row missing for ledger account");

        // Verify row was NOT created
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, customerAccount.getId());
        assertThat(count).isEqualTo(0L);

        // Case remains OPEN
        ReconciliationCase reconCase = caseRepository.findById(caseId).orElseThrow();
        assertThat(reconCase.getStatus()).isEqualTo(ReconciliationCaseStatus.OPEN);
    }

    @Test
    @DisplayName("Claimed case ownership enforced: another operator cannot execute repair")
    void claimedCaseOwnershipEnforcedOnRepair() {
        postJournal(customerAccount.getId(), clearingAccount.getId(), 20000L);
        UUID caseId = seedCase(customerAccount.getId(), ReconciliationProblemType.SNAPSHOT_MISMATCH);

        // OpsA claims the case
        ReconciliationCase reconCase = caseRepository.findById(caseId).orElseThrow();
        reconCase.claim(opsA.getId());
        caseRepository.saveAndFlush(reconCase);

        // OpsB attempts repair -> 409 Conflict
        assertThatThrownBy(() -> autoRepairService.repairSnapshot(caseId, opsB.getId()))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("claimed by another operator");
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private void postJournal(UUID customerAccId, UUID clearingAccId, long amountMinor) {
        ledgerPostingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(customerAccId, EntryDirection.CREDIT, Money.ofMinor(amountMinor, "INR")),
                        new PostingLine(clearingAccId, EntryDirection.DEBIT, Money.ofMinor(amountMinor, "INR"))
                )
        ));
    }

    private UUID insertRunningRun() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", id);
        return id;
    }

    private UUID seedCase(UUID entityId, ReconciliationProblemType problemType) {
        UUID runId = insertRunningRun();
        UUID itemId = UUID.randomUUID();
        ReconciliationLevel level = switch (problemType) {
            case SNAPSHOT_MISMATCH, SNAPSHOT_MISSING -> com.ledgerguard.reconciliation.domain.ReconciliationLevel.SNAPSHOT_CONSISTENCY;
            case UNBALANCED_JOURNAL, MALFORMED_JOURNAL -> com.ledgerguard.reconciliation.domain.ReconciliationLevel.JOURNAL_BALANCE;
            default -> com.ledgerguard.reconciliation.domain.ReconciliationLevel.PROVIDER_SETTLEMENT;
        };

        String entityType = (level == com.ledgerguard.reconciliation.domain.ReconciliationLevel.SNAPSHOT_CONSISTENCY)
                ? "LEDGER_ACCOUNT" : (level == com.ledgerguard.reconciliation.domain.ReconciliationLevel.JOURNAL_BALANCE ? "JOURNAL_TRANSACTION" : "FUNDING_OPERATION");

        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', ?, ?, ?, ?, 'test item', NOW())",
                itemId, runId, level.name(), problemType.name(), entityType, entityId);

        return jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
    }
}
