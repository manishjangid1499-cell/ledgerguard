package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.reconciliation.api.ReconciliationCaseResponse;
import com.ledgerguard.reconciliation.application.ReconciliationCaseManagementService;
import com.ledgerguard.reconciliation.domain.ReconciliationCase;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationResolutionAction;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Manual review workflow leaves all financial and business tables unchanged")
class ManualReviewNoMutationTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationCaseManagementService managementService;

    @Autowired
    private ReconciliationCaseRepository caseRepository;

    @Autowired
    private FundingOperationRepository fundingRepo;

    @Autowired
    private LedgerAccountRepository accountRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private JdbcTemplate jdbc;

    private User opsUser;
    private FundingOperation fundingOp;

    @BeforeEach
    void setUp() {
        opsUser = userRepo.save(new User(UUID.randomUUID(), "ops." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
        User customer = userRepo.save(new User(UUID.randomUUID(), "cust." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        LedgerAccount wallet = accountRepo.save(LedgerAccount.createCustomerAccount(customer.getId()));

        fundingOp = new FundingOperation(
                UUID.randomUUID(),
                customer.getId(),
                wallet.getId(),
                50000L,
                "INR",
                Instant.now()
        );
        fundingRepo.saveAndFlush(fundingOp);
        fundingOp.prepareSubmission(Instant.now().plusSeconds(3600));
        fundingRepo.saveAndFlush(fundingOp);
    }

    @Test
    @DisplayName("Manual review sets case RESOLVED without modifying any financial or business table")
    void manualReviewMutatesWorkflowOnly() {
        UUID caseId = seedProviderDiscrepancyCase(fundingOp.getId(), ReconciliationProblemType.PROVIDER_STATUS_MISMATCH);

        // Snapshot all financial and business tables before review action
        Map<String, List<Map<String, Object>>> preSnapshots = captureFinancialSnapshots();

        // OPS claims and resolves case with investigation notes
        managementService.claimCase(caseId, opsUser.getId());
        ReconciliationCaseResponse resolvedCase = managementService.resolveManually(
                caseId, opsUser.getId(), "Provider status mismatch investigated via external dashboard; no ledger adjustment required.");

        assertThat(resolvedCase.status()).isEqualTo("RESOLVED");
        assertThat(resolvedCase.resolutionAction()).isEqualTo("MANUAL_REVIEW_COMPLETED");
        assertThat(resolvedCase.resolvedByUserId()).isEqualTo(opsUser.getId());

        // Snapshot after review action
        Map<String, List<Map<String, Object>>> postSnapshots = captureFinancialSnapshots();

        // Assert all 9 financial and business tables remain 100% byte-for-byte/field identical
        assertThat(postSnapshots).isEqualTo(preSnapshots);

        // Re-read FundingOperation to assert business status is untouched
        FundingOperation postFunding = fundingRepo.findById(fundingOp.getId()).orElseThrow();
        assertThat(postFunding.getStatus()).isEqualTo(FundingStatus.PROCESSING);
        assertThat(postFunding.getProviderOperationId()).isNull();
    }

    private Map<String, List<Map<String, Object>>> captureFinancialSnapshots() {
        return Map.of(
                "journal_transactions", jdbc.queryForList("SELECT * FROM journal_transactions ORDER BY id"),
                "journal_entries", jdbc.queryForList("SELECT * FROM journal_entries ORDER BY id"),
                "ledger_balance_snapshots", jdbc.queryForList("SELECT * FROM ledger_balance_snapshots ORDER BY ledger_account_id"),
                "funding_operations", jdbc.queryForList("SELECT * FROM funding_operations ORDER BY id"),
                "payouts", jdbc.queryForList("SELECT * FROM payouts ORDER BY id"),
                "balance_holds", jdbc.queryForList("SELECT * FROM balance_holds ORDER BY id"),
                "provider_events", jdbc.queryForList("SELECT * FROM provider_events ORDER BY event_id"),
                "outbox_events", jdbc.queryForList("SELECT * FROM outbox_events ORDER BY id"),
                "idempotency_records", jdbc.queryForList("SELECT * FROM idempotency_records ORDER BY id")
        );
    }

    private UUID seedProviderDiscrepancyCase(UUID fundingId, ReconciliationProblemType problemType) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, observed_local_status, provider_status, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'PROVIDER_SETTLEMENT', ?, 'FUNDING_OPERATION', ?, 'PROCESSING', 'SUCCEEDED', 'Provider mismatch', NOW())",
                itemId, runId, problemType.name(), fundingId);

        return jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
    }
}
