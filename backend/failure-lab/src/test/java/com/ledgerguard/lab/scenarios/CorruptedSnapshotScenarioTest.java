package com.ledgerguard.lab.scenarios;

import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.AbstractFailureLabIntegrationTest;
import com.ledgerguard.lab.fault.SnapshotFaultInjector;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.reconciliation.api.SnapshotRepairResponse;
import com.ledgerguard.reconciliation.domain.ReconciliationCase;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Scenario 3 — Corrupted Snapshot (Real Phase 24 Recon & Phase 25 Repair)")
class CorruptedSnapshotScenarioTest extends AbstractFailureLabIntegrationTest {

    @Test
    @DisplayName("CORRUPTED_SNAPSHOT: Deliberate snapshot drift detected by real SnapshotConsistencyChecker and auto-repaired by real SnapshotAutoRepairService")
    void testCorruptedSnapshotRealProductionPath() throws Exception {
        // 1. Setup real user and account
        UUID userId = UUID.randomUUID();
        UUID opsUserId = UUID.randomUUID();

        userRepository.save(new User(userId, "snapCust." + userId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        userRepository.save(new User(opsUserId, "snapOps." + opsUserId + "@lab.local", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));

        LedgerAccount customerAccount = ledgerAccountRepository.save(LedgerAccount.createCustomerAccount(userId));
        LedgerAccount clearingAccount = ledgerAccountRepository.findByAccountType(AccountType.PSP_CLEARING)
                .orElseGet(() -> ledgerAccountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));

        // 2. Establish valid initial state through real LedgerPostingService: 50,000 minor units (INR 500.00)
        long initialFunds = 50000L;
        ledgerPostingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(clearingAccount.getId(), EntryDirection.DEBIT, Money.inr(initialFunds)),
                        new PostingLine(customerAccount.getId(), EntryDirection.CREDIT, Money.inr(initialFunds))
                )
        ));

        // 3. Oracle confirms initial state is 100% consistent
        try (Connection conn = dataSource.getConnection()) {
            List<InvariantCheckResult> baselineChecks = oracle.checkSnapshotIntegrity(conn);
            assertThat(baselineChecks).allMatch(InvariantCheckResult::passed);
        }

        // 4. Inject deliberate snapshot drift: corrupt customer snapshot to 10,000 minor units using SnapshotFaultInjector
        long corruptedBalance = 10000L;
        int rowsUpdated = SnapshotFaultInjector.corruptSnapshotBalance(LAB_TARGET, dataSource, customerAccount.getId(), corruptedBalance);
        assertThat(rowsUpdated).isEqualTo(1);

        // 5. Run REAL Phase 24 Level 2 Reconciliation Detection
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())",
                runId
        );

        // Execute real SnapshotConsistencyChecker
        snapshotConsistencyChecker.check(runId);

        // Verify discrepancy item was detected by real reconciliation
        Long discrepancyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation_items WHERE reconciliation_run_id = ? AND entity_id = ? AND classification = 'DISCREPANCY' AND problem_type = 'SNAPSHOT_MISMATCH'",
                Long.class, runId, customerAccount.getId()
        );
        assertThat(discrepancyCount).isEqualTo(1L);

        // Verify that database trigger auto-created an OPEN reconciliation case
        UUID caseId = jdbcTemplate.queryForObject(
                "SELECT rc.id FROM reconciliation_cases rc " +
                        "JOIN reconciliation_items ri ON ri.id = rc.reconciliation_item_id " +
                        "WHERE ri.reconciliation_run_id = ? AND ri.entity_id = ?",
                UUID.class, runId, customerAccount.getId()
        );
        assertThat(caseId).isNotNull();

        // 6. Run REAL Phase 25 Snapshot Auto-Repair Workflow
        SnapshotRepairResponse repairResponse = autoRepairService.repairSnapshot(caseId, opsUserId);
        assertThat(repairResponse.previousBalanceMinor()).isEqualTo("10000");
        assertThat(repairResponse.repairedBalanceMinor()).isEqualTo("50000");
        assertThat(repairResponse.resolutionAction()).isEqualTo("SNAPSHOT_REPAIRED");

        // Verify case is RESOLVED in database
        ReconciliationCase reconCase = reconciliationCaseRepository.findById(caseId).orElseThrow();
        assertThat(reconCase.getStatus()).isEqualTo(ReconciliationCaseStatus.RESOLVED);

        // 7. Independent Oracle Invariant Audit
        try (Connection conn = dataSource.getConnection()) {
            List<InvariantCheckResult> checks = new ArrayList<>();

            // A. Structural validity
            checks.addAll(oracle.checkJournalStructure(conn));

            // B. Debit total == Credit total
            checks.addAll(oracle.checkDebitCreditEquality(conn));

            // C. Snapshots reconstruct accurately from immutable POSTED journals (repaired snapshot matches journal sum)
            checks.addAll(oracle.checkSnapshotIntegrity(conn, customerAccount.getId()));

            // D. Available balance is valid
            checks.add(oracle.checkAvailableBalance(conn, customerAccount.getId()));

            assertThat(checks).allMatch(InvariantCheckResult::passed);
        }
    }
}
