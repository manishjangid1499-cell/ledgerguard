package com.ledgerguard.metrics;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IntegrityMetricsSnapshotReader — Comprehensive Testcontainers database integration tests")
class IntegrityMetricsSnapshotReaderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IntegrityMetricsSnapshotReader snapshotReader;

    @BeforeEach
    void setUp() {
        cleanPreExistingState();
    }

    @AfterEach
    void tearDown() {
        safeEnableTriggers();
        cleanPreExistingState();
    }

    private void safeEnableTriggers() {
        try {
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        } catch (Exception ignored) {}
    }

    private void cleanPreExistingState() {
        // Publish any pending outbox events so baseline lag is 0.0
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHED', published_at = GREATEST(NOW(), created_at) WHERE status = 'PENDING'");

        // Resolve any open or in-review cases so baseline discrepancies is 0
        UUID adminId = insertUser();
        jdbc.update("""
                UPDATE reconciliation_cases
                SET status = 'RESOLVED',
                    resolved_by_user_id = ?,
                    resolved_at = NOW(),
                    resolution_action = 'ALREADY_CONSISTENT',
                    updated_at = NOW()
                WHERE status IN ('OPEN', 'IN_REVIEW')
                """, adminId);
    }

    @Test
    @DisplayName("Case A: Healthy baseline yields 0 unbalanced journals, 0 discrepancies, and 0.0 lag")
    void caseA_healthyBaseline() {
        IntegritySnapshot snapshot = snapshotReader.readSnapshot();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.unbalancedJournalCount()).isEqualTo(0);
        assertThat(snapshot.reconciliationDiscrepancies()).isEqualTo(0);
        assertThat(snapshot.outboxLagSeconds()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Case B: Malformed/unbalanced journal causes unbalancedJournalCount = 1, recovers on repair")
    void caseB_malformedOrUnbalancedJournal() {
        UUID journalId = insertPostedJournal(50000L);

        // Baseline is healthy
        assertThat(snapshotReader.readSnapshot().unbalancedJournalCount()).isEqualTo(0);

        // Corrupt entry amount under temporary trigger bypass
        jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_immutability");
        try {
            jdbc.update("UPDATE journal_entries SET amount_minor = amount_minor + 100 " +
                        "WHERE journal_transaction_id = ? AND direction = 'CREDIT'", journalId);

            IntegritySnapshot corruptSnapshot = snapshotReader.readSnapshot();
            assertThat(corruptSnapshot.unbalancedJournalCount()).isEqualTo(1);
        } finally {
            // Restore entry balance
            jdbc.update("UPDATE journal_entries SET amount_minor = amount_minor - 100 " +
                        "WHERE journal_transaction_id = ? AND direction = 'CREDIT'", journalId);
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        }

        // Returns to 0
        assertThat(snapshotReader.readSnapshot().unbalancedJournalCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Case B2: Zero-entry POSTED journal is detected via LEFT JOIN (unbalancedJournalCount = 1)")
    void caseB2_zeroEntryPostedJournalDetectedViaLeftJoin() {
        UUID journalId = insertPostedJournal(25000L);

        // Baseline is healthy
        assertThat(snapshotReader.readSnapshot().unbalancedJournalCount()).isEqualTo(0);

        // Capture entry details for clean restoration
        var entries = jdbc.queryForList(
                "SELECT id, ledger_account_id, direction, amount_minor FROM journal_entries WHERE journal_transaction_id = ?",
                journalId);

        // Delete all entries under temporary trigger bypass to construct zero-entry POSTED state
        jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_immutability");
        try {
            jdbc.update("DELETE FROM journal_entries WHERE journal_transaction_id = ?", journalId);

            // Proves LEFT JOIN visibility: zero-entry POSTED journal yields count < 2 and is detected
            IntegritySnapshot zeroEntrySnapshot = snapshotReader.readSnapshot();
            assertThat(zeroEntrySnapshot.unbalancedJournalCount()).isEqualTo(1);
        } finally {
            // Restore deleted entries to leave database clean for other test cases
            for (var entry : entries) {
                jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                            "VALUES (?,?,?,?,?)",
                        entry.get("id"), journalId, entry.get("ledger_account_id"), entry.get("direction"), entry.get("amount_minor"));
            }
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        }

        // Verify trigger is ENABLED in pg_trigger catalog ('O' = origin/enabled)
        String triggerStatus = jdbc.queryForObject(
                "SELECT tgenabled FROM pg_trigger WHERE tgname = 'trg_journal_entries_immutability'", String.class);
        assertThat(triggerStatus).isEqualTo("O");

        // Baseline is healthy once again
        assertThat(snapshotReader.readSnapshot().unbalancedJournalCount()).isEqualTo(0);
    }



    @Test
    @DisplayName("Case C & D: OPEN discrepancy case yields count = 1, RESOLVED case yields count = 0")
    void caseCD_reconciliationDiscrepancyLifecycle() {
        UUID runId = insertReconRun();
        UUID itemId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Inserting an item with classification 'DISCREPANCY' auto-creates an OPEN case
        jdbc.update("""
                INSERT INTO reconciliation_items (id, reconciliation_run_id, classification, level, problem_type,
                    entity_type, entity_id, description, detected_at)
                VALUES (?, ?, 'DISCREPANCY', 'JOURNAL_BALANCE', 'UNBALANCED_JOURNAL', 'JOURNAL_TRANSACTION', ?, 'Test discrepancy', ?)
                """, itemId, runId, UUID.randomUUID(), now);

        // Case C: OPEN discrepancy case is counted
        IntegritySnapshot snapshotOpen = snapshotReader.readSnapshot();
        assertThat(snapshotOpen.reconciliationDiscrepancies()).isEqualTo(1);

        // Case D: Transition case to RESOLVED -> count drops to 0
        UUID resolverUserId = insertUser();
        jdbc.update("""
                UPDATE reconciliation_cases
                SET status = 'RESOLVED',
                    resolved_by_user_id = ?,
                    resolved_at = NOW(),
                    resolution_action = 'ALREADY_CONSISTENT',
                    updated_at = NOW()
                WHERE reconciliation_item_id = ?
                """, resolverUserId, itemId);

        IntegritySnapshot snapshotResolved = snapshotReader.readSnapshot();
        assertThat(snapshotResolved.reconciliationDiscrepancies()).isEqualTo(0);
    }

    @Test
    @DisplayName("Case E: Pending event ~30s old yields lag >= expected tolerance")
    void caseE_pendingOutboxEventLag() {
        UUID eventId = UUID.randomUUID();
        Timestamp pastOccurred = Timestamp.from(Instant.now().minusSeconds(35));
        Timestamp pastCreated = Timestamp.from(Instant.now().minusSeconds(30));

        jdbc.update("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at) " +
                    "VALUES (?, 'PAYMENT', ?, 'PaymentCreated', 1, '{}'::jsonb, 'PENDING', ?, ?)",
                eventId, UUID.randomUUID(), pastOccurred, pastCreated);

        IntegritySnapshot snapshot = snapshotReader.readSnapshot();
        assertThat(snapshot.outboxLagSeconds()).isGreaterThanOrEqualTo(29.0);
    }

    @Test
    @DisplayName("Case F: Future-dated pending fixture clamps outbox lag to 0.0 via GREATEST(0, ...)")
    void caseF_futureDatedPendingFixtureClampsToZero() {
        UUID eventId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp future = Timestamp.from(Instant.now().plusSeconds(60));

        jdbc.update("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at) " +
                    "VALUES (?, 'PAYMENT', ?, 'PaymentCreated', 1, '{}'::jsonb, 'PENDING', ?, ?)",
                eventId, UUID.randomUUID(), now, future);

        IntegritySnapshot snapshot = snapshotReader.readSnapshot();
        assertThat(snapshot.outboxLagSeconds()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Case G: Empty outbox yields lag = 0.0 via COALESCE(..., 0)")
    void caseG_emptyOutboxYieldsZeroLag() {
        // Ensure no pending events
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHED', published_at = GREATEST(NOW(), created_at) WHERE status = 'PENDING'");

        IntegritySnapshot snapshot = snapshotReader.readSnapshot();
        assertThat(snapshot.outboxLagSeconds()).isEqualTo(0.0);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                id, "snapshot-test-user-" + id + "@example.com", "hash", "CUSTOMER", "ACTIVE", now, now);
        return id;
    }

    private UUID insertReconRun() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) " +
                    "VALUES (?,?,?,?)",
                id, "RUNNING", "ON_DEMAND", Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertPostedJournal(long amountMinor) {
        UUID userId = insertUser();
        UUID customerAccId = insertAccount(userId, "CUSTOMER");
        UUID pspAccId = ensurePspClearingAccount();

        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?,?,?,?)",
                journalId, "DRAFT", "INR", now);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journalId, pspAccId, "DEBIT", amountMinor);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journalId, customerAccId, "CREDIT", amountMinor);

        jdbc.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalId);

        return journalId;
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
}
