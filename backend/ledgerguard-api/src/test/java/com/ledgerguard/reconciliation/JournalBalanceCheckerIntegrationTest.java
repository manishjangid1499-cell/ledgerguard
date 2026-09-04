package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.reconciliation.application.JournalBalanceChecker;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Level 1 — Journal Balance reconciliation integration tests.
 * Includes historical corruption tests using test-only trigger disable (try/finally).
 */
@DisplayName("JournalBalanceChecker — Level 1 integration tests")
class JournalBalanceCheckerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JournalBalanceChecker journalBalanceChecker;
    @Autowired private ReconciliationItemRepository itemRepository;

    private UUID runId;

    @BeforeEach
    void setUp() {
        runId = insertRunning();
    }

    @AfterEach
    void tearDown() {
        // Ensure triggers are always in the correct state after each test
        safeEnableTrigger();
    }

    // ── Healthy journal ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid balanced POSTED journal produces no item")
    void validJournalProducesNoItem() {
        UUID journalId = insertPostedJournal(10000L);

        long checked = journalBalanceChecker.check(runId);

        assertThat(checked).isGreaterThanOrEqualTo(1);
        assertThat(itemRepository.findAll()).noneMatch(i ->
                i.getEntityId().equals(journalId));
    }

    // ── Malformed journal cases ──────────────────────────────────────────────

    @Test
    @DisplayName("POSTED journal with zero entries detects MALFORMED_JOURNAL")
    void zeroEntryJournalDetectedAsMalformed() {
        UUID journalId = insertEmptyPostedJournalViaBypass();

        journalBalanceChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(journalId)
                        && i.getProblemType() == ReconciliationProblemType.MALFORMED_JOURNAL);

        // No repair — journal transaction row still exists with POSTED status
        String status = jdbc.queryForObject(
                "SELECT status FROM journal_transactions WHERE id = ?", String.class, journalId);
        assertThat(status).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("V2 trigger normally rejects direct mutation of POSTED journal entries")
    void v2TriggerBlocksDirectEntryMutationNormally() {
        UUID journalId = insertPostedJournal(10000L);

        // With trigger enabled, mutating a POSTED entry must throw
        assertThatThrownBy(() ->
                jdbc.update("UPDATE journal_entries SET amount_minor = amount_minor + 1 " +
                            "WHERE journal_transaction_id = ? AND direction = 'CREDIT'", journalId)
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Test-only trigger disable: UNBALANCED_JOURNAL detected, no repair performed")
    void corruptedAmountDetectedNotRepaired() {
        UUID journalId = insertPostedJournal(10000L);

        // Remember entry amounts before corruption
        Long creditBefore = jdbc.queryForObject(
                "SELECT amount_minor FROM journal_entries WHERE journal_transaction_id = ? AND direction = 'CREDIT'",
                Long.class, journalId);

        // Test-only: disable immutability trigger, corrupt one entry, re-enable
        jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_immutability");
        try {
            jdbc.update("UPDATE journal_entries SET amount_minor = amount_minor + 99 " +
                        "WHERE journal_transaction_id = ? AND direction = 'CREDIT'", journalId);
        } finally {
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        }

        // Prove trigger is ENABLED again using PostgreSQL catalog and forbidden mutation
        String tgEnabled1 = jdbc.queryForObject(
                "SELECT tgenabled FROM pg_trigger WHERE tgname = 'trg_journal_entries_immutability'", String.class);
        assertThat(tgEnabled1).isEqualTo("O");
        assertThatThrownBy(() ->
                jdbc.update("UPDATE journal_entries SET amount_minor = amount_minor + 1 WHERE journal_transaction_id = ?", journalId)
        ).isInstanceOf(Exception.class);

        journalBalanceChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(journalId)
                        && i.getProblemType() == ReconciliationProblemType.UNBALANCED_JOURNAL);

        // No repair — corrupted value still in place
        Long creditAfter = jdbc.queryForObject(
                "SELECT amount_minor FROM journal_entries WHERE journal_transaction_id = ? AND direction = 'CREDIT'",
                Long.class, journalId);
        assertThat(creditAfter).isEqualTo(creditBefore + 99);
    }

    @Test
    @DisplayName("Test-only trigger disable: zero-entry MALFORMED_JOURNAL detected, transaction row preserved")
    void zeroEntryCorruptionDetectedNotRepaired() {
        UUID journalId = insertPostedJournal(5000L);

        jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_immutability");
        try {
            jdbc.update("DELETE FROM journal_entries WHERE journal_transaction_id = ?", journalId);
        } finally {
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        }

        // Prove trigger is ENABLED again using PostgreSQL catalog
        String tgEnabled2 = jdbc.queryForObject(
                "SELECT tgenabled FROM pg_trigger WHERE tgname = 'trg_journal_entries_immutability'", String.class);
        assertThat(tgEnabled2).isEqualTo("O");

        journalBalanceChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(journalId)
                        && i.getProblemType() == ReconciliationProblemType.MALFORMED_JOURNAL);

        // Journal transaction row still exists; only entries were deleted in test setup
        String status = jdbc.queryForObject(
                "SELECT status FROM journal_transactions WHERE id = ?", String.class, journalId);
        assertThat(status).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("DRAFT journal entries are NOT included in Level 1 scan (POSTED scope only)")
    void draftJournalNotScanned() {
        // Insert a DRAFT journal — should not appear in Level 1 results
        UUID draftId = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?,?,?,?)",
                draftId, "DRAFT", "INR", Timestamp.from(Instant.now()));

        long checked = journalBalanceChecker.check(runId);

        // The DRAFT journal should NOT be counted or flagged
        assertThat(itemRepository.findAll()).noneMatch(i -> i.getEntityId().equals(draftId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID insertRunning() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?,?,?,?)",
                id, "RUNNING", "ON_DEMAND", Timestamp.from(Instant.now()));
        return id;
    }

    /**
     * Inserts a valid POSTED journal with one DEBIT and one CREDIT entry of equal amount.
     * The V3 snapshot trigger fires and updates the relevant account snapshot.
     */
    private UUID insertPostedJournal(long amountMinor) {
        // Ensure we have ledger accounts with snapshots
        UUID userId = insertUser();
        UUID customerAccId = insertAccount(userId, "CUSTOMER");
        UUID pspAccId = ensurePspClearingAccount();

        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Insert as DRAFT first (trigger only fires on status change)
        jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?,?,?,?)",
                journalId, "DRAFT", "INR", now);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journalId, pspAccId, "DEBIT", amountMinor);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journalId, customerAccId, "CREDIT", amountMinor);

        // Transition to POSTED — triggers V2 balance check and V3 snapshot update
        jdbc.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalId);

        return journalId;
    }

    /**
     * Creates an empty POSTED journal by inserting with status=POSTED directly
     * after disabling the V2 balance-check trigger (test-only, try/finally guarded).
     */
    private UUID insertEmptyPostedJournalViaBypass() {
        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // First insert as DRAFT (no trigger issue for INSERT)
        jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?,?,?,?)",
                journalId, "DRAFT", "INR", now);

        // Bypass the balance-check trigger to transition to POSTED with zero entries
        jdbc.execute("ALTER TABLE journal_transactions DISABLE TRIGGER trg_journal_transactions_balance_check");
        try {
            jdbc.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                    now, journalId);
        } finally {
            jdbc.execute("ALTER TABLE journal_transactions ENABLE TRIGGER trg_journal_transactions_balance_check");
        }
        return journalId;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                id, "recon-test-" + id + "@example.com", "hash", "CUSTOMER", "ACTIVE", now, now);
        return id;
    }

    private UUID insertAccount(UUID userId, String accountType) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        UUID ownerId = ("CUSTOMER".equals(accountType) || "MERCHANT".equals(accountType)) ? userId : null;
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
