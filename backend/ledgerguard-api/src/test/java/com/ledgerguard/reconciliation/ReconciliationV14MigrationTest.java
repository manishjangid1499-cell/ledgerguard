package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V14 migration constraint and trigger tests.
 * Verifies all DB-level invariants before any application code runs reconciliation.
 */
@DisplayName("V14 reconciliation_runs / reconciliation_items DB constraints")
class ReconciliationV14MigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    // ── reconciliation_runs trigger tests ────────────────────────────────────

    @Test
    @DisplayName("INSERT run with non-RUNNING status is rejected by trigger")
    void insertRunNonRunningRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO reconciliation_runs " +
                            "(id, status, trigger_source, started_at) VALUES (?,?,?,?)",
                        id, "COMPLETED", "ON_DEMAND", Timestamp.from(Instant.now()))
        ).hasMessageContaining("RUNNING");
    }

    @Test
    @DisplayName("UPDATE COMPLETED run to any status is rejected by trigger")
    void updateCompletedRunRejected() {
        UUID id = insertRunning();
        finalizeRun(id, "COMPLETED");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_runs SET failure_reason = 'x' WHERE id = ?", id)
        ).hasMessageContaining("Terminal");
    }

    @Test
    @DisplayName("UPDATE FAILED run is rejected by trigger")
    void updateFailedRunRejected() {
        UUID id = insertRunning();
        finalizeRun(id, "FAILED");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_runs SET failure_reason = 'x' WHERE id = ?", id)
        ).hasMessageContaining("Terminal");
    }

    @Test
    @DisplayName("DELETE run is rejected by trigger")
    void deleteRunRejected() {
        UUID id = insertRunning();
        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM reconciliation_runs WHERE id = ?", id)
        ).hasMessageContaining("cannot be deleted");
    }

    @Test
    @DisplayName("RUNNING -> COMPLETED transition is accepted")
    void runningToCompletedAccepted() {
        UUID id = insertRunning();
        finalizeRun(id, "COMPLETED");
        String status = jdbc.queryForObject(
                "SELECT status FROM reconciliation_runs WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("RUNNING -> FAILED transition is accepted")
    void runningToFailedAccepted() {
        UUID id = insertRunning();
        finalizeRun(id, "FAILED");
        String status = jdbc.queryForObject(
                "SELECT status FROM reconciliation_runs WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("FAILED");
    }

    // ── reconciliation_items trigger tests ───────────────────────────────────

    @Test
    @DisplayName("INSERT item into RUNNING run is accepted")
    void insertItemIntoRunningAccepted() {
        UUID runId = insertRunning();
        UUID itemId = insertItem(runId, "JOURNAL_TRANSACTION", UUID.randomUUID());
        assertThat(itemId).isNotNull();
    }

    @Test
    @DisplayName("INSERT item into COMPLETED run is rejected by trigger")
    void insertItemIntoCompletedRunRejected() {
        UUID runId = insertRunning();
        finalizeRun(runId, "COMPLETED");

        assertThatThrownBy(() -> insertItem(runId, "JOURNAL_TRANSACTION", UUID.randomUUID()))
                .hasMessageContaining("terminal status");
    }

    @Test
    @DisplayName("INSERT item into FAILED run is rejected by trigger")
    void insertItemIntoFailedRunRejected() {
        UUID runId = insertRunning();
        finalizeRun(runId, "FAILED");

        assertThatThrownBy(() -> insertItem(runId, "JOURNAL_TRANSACTION", UUID.randomUUID()))
                .hasMessageContaining("terminal status");
    }

    @Test
    @DisplayName("UPDATE item is rejected by trigger")
    void updateItemRejected() {
        UUID runId = insertRunning();
        UUID itemId = insertItem(runId, "JOURNAL_TRANSACTION", UUID.randomUUID());

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_items SET description = 'tampered' WHERE id = ?", itemId)
        ).hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("DELETE item is rejected by trigger")
    void deleteItemRejected() {
        UUID runId = insertRunning();
        UUID itemId = insertItem(runId, "JOURNAL_TRANSACTION", UUID.randomUUID());

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM reconciliation_items WHERE id = ?", itemId)
        ).hasMessageContaining("immutable");
    }

    // ── cross-field constraint tests ─────────────────────────────────────────

    @Test
    @DisplayName("JOURNAL_BALANCE item with entity_type LEDGER_ACCOUNT is rejected (cross-field)")
    void journalBalanceWithWrongEntityTypeRejected() {
        UUID runId = insertRunning();
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO reconciliation_items " +
                            "(id,reconciliation_run_id,classification,level,problem_type,entity_type,entity_id,description,detected_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), runId,
                        "DISCREPANCY", "JOURNAL_BALANCE", "UNBALANCED_JOURNAL",
                        "LEDGER_ACCOUNT", UUID.randomUUID(),
                        "bad", Timestamp.from(Instant.now()))
        ).hasMessageContaining("chk_recon_items_level_entity");
    }

    @Test
    @DisplayName("JOURNAL_BALANCE item with UNRESOLVED classification is rejected")
    void journalBalanceUnresolvedRejected() {
        UUID runId = insertRunning();
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO reconciliation_items " +
                            "(id,reconciliation_run_id,classification,level,problem_type,entity_type,entity_id,description,detected_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), runId,
                        "UNRESOLVED", "JOURNAL_BALANCE", "UNBALANCED_JOURNAL",
                        "JOURNAL_TRANSACTION", UUID.randomUUID(),
                        "bad", Timestamp.from(Instant.now()))
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("SNAPSHOT_CONSISTENCY item with UNRESOLVED classification is rejected")
    void snapshotConsistencyUnresolvedRejected() {
        UUID runId = insertRunning();
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO reconciliation_items " +
                            "(id,reconciliation_run_id,classification,level,problem_type,entity_type,entity_id,description,detected_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), runId,
                        "UNRESOLVED", "SNAPSHOT_CONSISTENCY", "SNAPSHOT_MISMATCH",
                        "LEDGER_ACCOUNT", UUID.randomUUID(),
                        "bad", Timestamp.from(Instant.now()))
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("PROVIDER_SETTLEMENT item with wrong problem_type (journal type) is rejected")
    void providerSettlementWithJournalProblemTypeRejected() {
        UUID runId = insertRunning();
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO reconciliation_items " +
                            "(id,reconciliation_run_id,classification,level,problem_type,entity_type,entity_id,description,detected_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), runId,
                        "DISCREPANCY", "PROVIDER_SETTLEMENT", "UNBALANCED_JOURNAL",
                        "FUNDING_OPERATION", UUID.randomUUID(),
                        "bad", Timestamp.from(Instant.now()))
        ).hasMessageContaining("chk_recon_items_level_problem");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertRunning() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?,?,?,?)",
                id, "RUNNING", "ON_DEMAND", Timestamp.from(Instant.now()));
        return id;
    }

    private void finalizeRun(UUID id, String status) {
        jdbc.update("UPDATE reconciliation_runs SET status=?, completed_at=? WHERE id=?",
                status, Timestamp.from(Instant.now()), id);
    }

    private UUID insertItem(UUID runId, String entityType, UUID entityId) {
        UUID itemId = UUID.randomUUID();
        String level = entityType.equals("JOURNAL_TRANSACTION") ? "JOURNAL_BALANCE"
                : entityType.equals("LEDGER_ACCOUNT") ? "SNAPSHOT_CONSISTENCY"
                : "PROVIDER_SETTLEMENT";
        String problemType = entityType.equals("JOURNAL_TRANSACTION") ? "UNBALANCED_JOURNAL"
                : entityType.equals("LEDGER_ACCOUNT") ? "SNAPSHOT_MISMATCH"
                : "PROVIDER_STATUS_MISMATCH";

        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id,reconciliation_run_id,classification,level,problem_type,entity_type,entity_id,description,detected_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
                itemId, runId, "DISCREPANCY", level, problemType,
                entityType, entityId, "test item", Timestamp.from(Instant.now()));
        return itemId;
    }
}
