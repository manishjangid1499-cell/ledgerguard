package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.reconciliation.application.ReconciliationEngine;
import com.ledgerguard.reconciliation.application.ScheduledReconciliationJob;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationRun;
import com.ledgerguard.reconciliation.domain.ReconciliationRunStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationTrigger;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@DisplayName("ReconciliationEngineIntegrationTest — Three-level integrated run & no-repair proof")
class ReconciliationEngineIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ReconciliationEngine reconciliationEngine;
    @Autowired private ScheduledReconciliationJob scheduledJob;
    @Autowired private ReconciliationRunRepository runRepository;
    @Autowired private ReconciliationItemRepository itemRepository;
    @Autowired private PspClient mockPspClient;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID customerAccountId;
    private UUID pspClearingAccountId;

    @TestConfiguration
    static class MockPspConfig {
        @Bean
        @Primary
        public PspClient pspClient() {
            return mock(PspClient.class);
        }
    }

    @BeforeEach
    void setUp() {
        reset(mockPspClient);
        userId = insertUser();
        customerAccountId = insertAccount(userId, "CUSTOMER");
        pspClearingAccountId = ensurePspClearingAccount();
    }

    @AfterEach
    void tearDown() {
        safeEnableTrigger();
    }

    @Test
    @DisplayName("Three-level integrated run: seeds 1 unbalanced journal, 1 corrupted snapshot, 1 provider mismatch -> detects all 3, no repair")
    void threeLevelIntegratedRunDetectsDiscrepanciesWithoutRepair() {
        // 1. Seed Level 1 discrepancy (unbalanced journal via test-only trigger disable)
        UUID journalId = postJournal(pspClearingAccountId, customerAccountId, 10000L);
        jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_immutability");
        try {
            jdbc.update("UPDATE journal_entries SET amount_minor = amount_minor + 50 WHERE journal_transaction_id = ? AND direction = 'CREDIT'", journalId);
        } finally {
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        }

        // 2. Seed Level 2 discrepancy (corrupted snapshot for another account)
        UUID user2 = insertUser();
        UUID customer2 = insertAccount(user2, "CUSTOMER");
        postJournal(pspClearingAccountId, customer2, 4000L);
        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = balance_minor + 12345 WHERE ledger_account_id = ?", customer2);

        // 3. Seed Level 3 discrepancy (SUCCEEDED funding but provider returns FAILED)
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", providerOpId, 7000L);
        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "7000", "INR", "FAILED", null, null, false))
        );

        // Record financial table state before reconciliation run (row counts AND row-level data)
        java.util.List<java.util.Map<String, Object>> jtBefore = jdbc.queryForList("SELECT * FROM journal_transactions ORDER BY id");
        java.util.List<java.util.Map<String, Object>> jeBefore = jdbc.queryForList("SELECT * FROM journal_entries ORDER BY id");
        java.util.List<java.util.Map<String, Object>> lbsBefore = jdbc.queryForList("SELECT * FROM ledger_balance_snapshots ORDER BY ledger_account_id");
        java.util.List<java.util.Map<String, Object>> foBefore = jdbc.queryForList("SELECT * FROM funding_operations ORDER BY id");
        java.util.List<java.util.Map<String, Object>> pBefore = jdbc.queryForList("SELECT * FROM payouts ORDER BY id");
        java.util.List<java.util.Map<String, Object>> bhBefore = jdbc.queryForList("SELECT * FROM balance_holds ORDER BY id");
        java.util.List<java.util.Map<String, Object>> peBefore = jdbc.queryForList("SELECT * FROM provider_events ORDER BY event_id");
        java.util.List<java.util.Map<String, Object>> oeBefore = jdbc.queryForList("SELECT * FROM outbox_events ORDER BY id");
        java.util.List<java.util.Map<String, Object>> irBefore = jdbc.queryForList("SELECT * FROM idempotency_records ORDER BY id");

        // Execute reconciliation engine
        UUID runId = reconciliationEngine.run(ReconciliationTrigger.ON_DEMAND);

        // Verify Run Status & Counters
        ReconciliationRun run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(run.getDiscrepancyCount()).isGreaterThanOrEqualTo(3);

        // Verify items created across all 3 levels
        assertThat(itemRepository.findAll()).anyMatch(i ->
                i.getReconciliationRunId().equals(runId)
                        && i.getLevel() == ReconciliationLevel.JOURNAL_BALANCE
                        && i.getProblemType() == ReconciliationProblemType.UNBALANCED_JOURNAL
                        && i.getEntityId().equals(journalId));

        assertThat(itemRepository.findAll()).anyMatch(i ->
                i.getReconciliationRunId().equals(runId)
                        && i.getLevel() == ReconciliationLevel.SNAPSHOT_CONSISTENCY
                        && i.getProblemType() == ReconciliationProblemType.SNAPSHOT_MISMATCH
                        && i.getEntityId().equals(customer2));

        assertThat(itemRepository.findAll()).anyMatch(i ->
                i.getReconciliationRunId().equals(runId)
                        && i.getLevel() == ReconciliationLevel.PROVIDER_SETTLEMENT
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_STATUS_MISMATCH
                        && i.getEntityId().equals(fundingId));

        // Assert NO REPAIR — all 9 business/operational tables strictly unchanged in count and content
        assertThat(jdbc.queryForList("SELECT * FROM journal_transactions ORDER BY id")).isEqualTo(jtBefore);
        assertThat(jdbc.queryForList("SELECT * FROM journal_entries ORDER BY id")).isEqualTo(jeBefore);
        assertThat(jdbc.queryForList("SELECT * FROM ledger_balance_snapshots ORDER BY ledger_account_id")).isEqualTo(lbsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM funding_operations ORDER BY id")).isEqualTo(foBefore);
        assertThat(jdbc.queryForList("SELECT * FROM payouts ORDER BY id")).isEqualTo(pBefore);
        assertThat(jdbc.queryForList("SELECT * FROM balance_holds ORDER BY id")).isEqualTo(bhBefore);
        assertThat(jdbc.queryForList("SELECT * FROM provider_events ORDER BY event_id")).isEqualTo(peBefore);
        assertThat(jdbc.queryForList("SELECT * FROM outbox_events ORDER BY id")).isEqualTo(oeBefore);
        assertThat(jdbc.queryForList("SELECT * FROM idempotency_records ORDER BY id")).isEqualTo(irBefore);

        // Snapshot is still corrupted (unrepaired)
        Long snapshotVal = jdbc.queryForObject("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, customer2);
        assertThat(snapshotVal).isEqualTo(4000L + 12345L);

        // Funding operation is still SUCCEEDED (no status downgrade)
        String fundingStatus = jdbc.queryForObject("SELECT status FROM funding_operations WHERE id = ?", String.class, fundingId);
        assertThat(fundingStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("ScheduledReconciliationJob triggers run with SCHEDULED trigger source")
    void scheduledJobDelegatesToEngineWithScheduledTrigger() {
        long runCountBefore = runRepository.count();

        scheduledJob.runScheduled();

        long runCountAfter = runRepository.count();
        assertThat(runCountAfter).isEqualTo(runCountBefore + 1);

        ReconciliationRun latestRun = runRepository.findAll().stream()
                .filter(r -> r.getTriggerSource() == ReconciliationTrigger.SCHEDULED)
                .reduce((first, second) -> second)
                .orElseThrow();

        assertThat(latestRun.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(latestRun.getTriggerSource()).isEqualTo(ReconciliationTrigger.SCHEDULED);
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count != null ? count : 0;
    }

    private UUID postJournal(UUID debitAcc, UUID creditAcc, long amountMinor) {
        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?,?,?,?)",
                journalId, "DRAFT", "INR", now);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journalId, debitAcc, "DEBIT", amountMinor);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journalId, creditAcc, "CREDIT", amountMinor);
        jdbc.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalId);
        return journalId;
    }

    private void insertFunding(UUID id, String status, UUID providerOpId, long amountMinor) {
        Timestamp now = Timestamp.from(Instant.now());

        // 1. Initial insert as CREATED
        jdbc.update("INSERT INTO funding_operations " +
                    "(id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                    "VALUES (?, ?, ?, ?, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                id, userId, customerAccountId, amountMinor, now);

        if ("CREATED".equals(status)) {
            return;
        }

        // Move to PROCESSING
        jdbc.update("UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, id);

        if ("SUCCEEDED".equals(status)) {
            UUID journalId = UUID.randomUUID();
            jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                    journalId, now);
            jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'DEBIT', ?)",
                    UUID.randomUUID(), journalId, pspClearingAccountId, amountMinor);
            jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'CREDIT', ?)",
                    UUID.randomUUID(), journalId, customerAccountId, amountMinor);
            jdbc.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                    now, journalId);

            jdbc.update("UPDATE funding_operations SET status = 'SUCCEEDED', provider_operation_id = ?, journal_transaction_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                    providerOpId, journalId, now, id);
        }
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                id, "recon-engine-" + id + "@example.com", "hash", "CUSTOMER", "ACTIVE", now, now);
        return id;
    }

    private UUID insertAccount(UUID uId, String accountType) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        UUID ownerId = ("CUSTOMER".equals(accountType) || "MERCHANT".equals(accountType)) ? uId : null;
        jdbc.update("INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                id, ownerId, accountType, "INR", "ACTIVE", now, now);
        return id;
    }

    private UUID ensurePspClearingAccount() {
        UUID existing = jdbc.query(
                "SELECT id FROM ledger_accounts WHERE account_type = 'PSP_CLEARING' AND status = 'ACTIVE' LIMIT 1",
                rs -> rs.next() ? UUID.fromString(rs.getString("id")) : null);
        if (existing != null) return existing;

        return insertAccount(null, "PSP_CLEARING");
    }

    private void safeEnableTrigger() {
        try {
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        } catch (Exception ignored) {}
        try {
            jdbc.execute("ALTER TABLE journal_transactions ENABLE TRIGGER trg_journal_transactions_balance_check");
        } catch (Exception ignored) {}
    }
}
