package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.reconciliation.application.SnapshotConsistencyChecker;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SnapshotConsistencyCheckerIntegrationTest — Level 2 snapshot consistency integration tests")
class SnapshotConsistencyCheckerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SnapshotConsistencyChecker snapshotConsistencyChecker;
    @Autowired private ReconciliationItemRepository itemRepository;

    private UUID runId;
    private UUID userId;
    private UUID customerAccountId;
    private UUID pspClearingAccountId;

    @BeforeEach
    void setUp() {
        runId = insertRunning();
        userId = insertUser();
        customerAccountId = insertAccount(userId, "CUSTOMER");
        pspClearingAccountId = insertAccount(null, "PSP_CLEARING");
    }

    @Test
    @DisplayName("Healthy snapshot: matches reconstructed posted balance -> no item")
    void healthySnapshotProducesNoDiscrepancy() {
        postJournal(pspClearingAccountId, customerAccountId, 10000L);

        long checked = snapshotConsistencyChecker.check(runId);

        assertThat(checked).isGreaterThanOrEqualTo(2);
        assertThat(itemRepository.findAll()).noneMatch(i ->
                i.getReconciliationRunId().equals(runId)
                        && (i.getEntityId().equals(customerAccountId) || i.getEntityId().equals(pspClearingAccountId)));
    }

    @Test
    @DisplayName("DRAFT journal entries are excluded from Level 2 reconstruction")
    void draftJournalEntriesExcludedFromReconstruction() {
        // 1. Valid POSTED journal of 5000L
        postJournal(pspClearingAccountId, customerAccountId, 5000L);

        // 2. DRAFT journal with entries for customerAccountId (amount 20000L)
        UUID draftJournalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?,?,?,?)",
                draftJournalId, "DRAFT", "INR", now);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), draftJournalId, customerAccountId, "CREDIT", 20000L);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), draftJournalId, pspClearingAccountId, "DEBIT", 20000L);

        // Reconstruct: customerAccount snapshot should be 5000L and match. DRAFT 20000L must NOT be added.
        snapshotConsistencyChecker.check(runId);

        assertThat(itemRepository.findAll()).noneMatch(i ->
                i.getReconciliationRunId().equals(runId) && i.getEntityId().equals(customerAccountId));
    }

    @Test
    @DisplayName("Corrupted snapshot balance detects SNAPSHOT_MISMATCH, snapshot not repaired")
    void corruptedSnapshotDetectedNotRepaired() {
        postJournal(pspClearingAccountId, customerAccountId, 7500L);

        // Corrupt snapshot directly via JDBC
        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = balance_minor + 99999 WHERE ledger_account_id = ?", customerAccountId);

        snapshotConsistencyChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getReconciliationRunId().equals(runId)
                        && i.getEntityId().equals(customerAccountId)
                        && i.getProblemType() == ReconciliationProblemType.SNAPSHOT_MISMATCH
                        && i.getExpectedValue().compareTo(BigDecimal.valueOf(7500L)) == 0
                        && i.getActualValue().compareTo(BigDecimal.valueOf(7500L + 99999L)) == 0);

        // No repair
        Long snapshotAfter = jdbc.queryForObject(
                "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?",
                Long.class, customerAccountId);
        assertThat(snapshotAfter).isEqualTo(7500L + 99999L);
    }

    @Test
    @DisplayName("Missing snapshot row detects SNAPSHOT_MISSING")
    void missingSnapshotRowDetected() {
        postJournal(pspClearingAccountId, customerAccountId, 3000L);

        // Delete snapshot row directly
        jdbc.update("DELETE FROM ledger_balance_snapshots WHERE ledger_account_id = ?", customerAccountId);

        snapshotConsistencyChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getReconciliationRunId().equals(runId)
                        && i.getEntityId().equals(customerAccountId)
                        && i.getProblemType() == ReconciliationProblemType.SNAPSHOT_MISSING
                        && i.getExpectedValue().compareTo(BigDecimal.valueOf(3000L)) == 0
                        && i.getActualValue() == null);
    }

    private void postJournal(UUID debitAcc, UUID creditAcc, long amountMinor) {
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
    }

    private UUID insertRunning() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?,?,?,?)",
                id, "RUNNING", "ON_DEMAND", Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                id, "recon-l2-" + id + "@example.com", "hash", "CUSTOMER", "ACTIVE", now, now);
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
}
