package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V15 reconciliation_cases DB constraints and trigger lifecycle")
class ReconciliationV15MigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("1. OPEN case: assigned_to_user_id is NULL initially")
    void openCaseHasNullAssignedActorInitially() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);

        var row = jdbc.queryForMap("SELECT status, assigned_to_user_id, resolved_by_user_id FROM reconciliation_cases WHERE id = ?", caseId);
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("assigned_to_user_id")).isNull();
        assertThat(row.get("resolved_by_user_id")).isNull();
    }

    @Test
    @DisplayName("2. Claim by OPS_A sets assigned_to_user_id = OPS_A")
    void claimByOpsASetsAssignedActor() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);
        UUID opsA = insertTestUser("OPS");

        jdbc.update("UPDATE reconciliation_cases SET status = 'IN_REVIEW', assigned_to_user_id = ?, updated_at = NOW() WHERE id = ?",
                opsA, caseId);

        var row = jdbc.queryForMap("SELECT status, assigned_to_user_id FROM reconciliation_cases WHERE id = ?", caseId);
        assertThat(row.get("status")).isEqualTo("IN_REVIEW");
        assertThat(row.get("assigned_to_user_id")).isEqualTo(opsA);
    }

    @Test
    @DisplayName("3. Direct SQL attempt OPS_A -> OPS_B is rejected by trigger")
    void reassignmentFromOpsAToOpsBRejected() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);
        UUID opsA = insertTestUser("OPS");
        UUID opsB = insertTestUser("OPS");

        jdbc.update("UPDATE reconciliation_cases SET status = 'IN_REVIEW', assigned_to_user_id = ?, updated_at = NOW() WHERE id = ?",
                opsA, caseId);

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_cases SET assigned_to_user_id = ? WHERE id = ?", opsB, caseId)
        ).hasMessageContaining("cannot be reassigned");
    }

    @Test
    @DisplayName("4. Direct SQL attempt OPS_A -> NULL is rejected by trigger (null-safe IS DISTINCT FROM)")
    void unassignmentFromOpsAToNullRejected() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);
        UUID opsA = insertTestUser("OPS");

        jdbc.update("UPDATE reconciliation_cases SET status = 'IN_REVIEW', assigned_to_user_id = ?, updated_at = NOW() WHERE id = ?",
                opsA, caseId);

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_cases SET assigned_to_user_id = NULL WHERE id = ?", caseId)
        ).hasMessageContaining("cannot be reassigned or unassigned");
    }

    @Test
    @DisplayName("5. Same-value OPS_A -> OPS_A update is allowed for idempotent behavior")
    void sameValueOpsAToOpsAAllowed() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);
        UUID opsA = insertTestUser("OPS");

        jdbc.update("UPDATE reconciliation_cases SET status = 'IN_REVIEW', assigned_to_user_id = ?, updated_at = NOW() WHERE id = ?",
                opsA, caseId);

        int updated = jdbc.update("UPDATE reconciliation_cases SET assigned_to_user_id = ?, updated_at = NOW() WHERE id = ?", opsA, caseId);
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("INSERT case with non-OPEN status is rejected by trigger")
    void insertCaseNonOpenRejected() {
        UUID ops = insertTestUser("OPS");
        UUID randomItemId = UUID.randomUUID();
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO reconciliation_cases (id, reconciliation_item_id, status, assigned_to_user_id, opened_at, updated_at) " +
                            "VALUES (?, ?, 'IN_REVIEW', ?, NOW(), NOW())", UUID.randomUUID(), randomItemId, ops)
        ).hasMessageContaining("reconciliation_cases must be inserted with status OPEN");
    }

    @Test
    @DisplayName("UPDATE on RESOLVED case is rejected by trigger (terminal immutability)")
    void updateResolvedCaseRejected() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);
        UUID ops = insertTestUser("OPS");

        jdbc.update("UPDATE reconciliation_cases SET status = 'RESOLVED', resolved_by_user_id = ?, " +
                    "resolution_action = 'MANUAL_REVIEW_COMPLETED', resolution_note = 'Investigated', " +
                    "resolved_at = NOW(), updated_at = NOW() WHERE id = ?", ops, caseId);

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_cases SET resolution_note = 'Changed note' WHERE id = ?", caseId)
        ).hasMessageContaining("Terminal reconciliation_case");
    }

    @Test
    @DisplayName("DELETE on reconciliation_cases is unconditionally rejected by trigger")
    void deleteCaseRejected() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);

        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM reconciliation_cases WHERE id = ?", caseId)
        ).hasMessageContaining("cannot be deleted");
    }

    @Test
    @DisplayName("Modifying immutable identity columns (id, reconciliation_item_id, opened_at) is rejected")
    void modifyingIdentityColumnsRejected() {
        UUID itemId1 = insertReconciliationItem();
        UUID itemId2 = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId1);

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_cases SET reconciliation_item_id = ? WHERE id = ?", itemId2, caseId)
        ).hasMessageContaining("Immutable fields");
    }

    @Test
    @DisplayName("Trigger on reconciliation_items automatically creates OPEN case for newly detected item")
    void autoCreateCaseTriggerOnItemInsert() {
        UUID runId = insertRunningRun();
        UUID itemId = UUID.randomUUID();

        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'SNAPSHOT_CONSISTENCY', 'SNAPSHOT_MISMATCH', 'LEDGER_ACCOUNT', ?, 'mismatch', NOW())",
                itemId, runId, UUID.randomUUID());

        var caseRow = jdbc.queryForMap("SELECT * FROM reconciliation_cases WHERE reconciliation_item_id = ?", itemId);
        assertThat(caseRow).isNotNull();
        assertThat(caseRow.get("status")).isEqualTo("OPEN");
        assertThat(caseRow.get("assigned_to_user_id")).isNull();
        assertThat(caseRow.get("resolved_by_user_id")).isNull();
    }

    @Test
    @DisplayName("Direct SQL attempt with updated_at < opened_at is rejected by chk_recon_cases_timestamps")
    void updatedAtBeforeOpenedAtRejected() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_cases SET updated_at = opened_at - INTERVAL '1 minute' WHERE id = ?", caseId)
        ).hasMessageContaining("chk_recon_cases_timestamps");
    }

    @Test
    @DisplayName("Direct SQL attempt with resolved_at < opened_at is rejected by chk_recon_cases_timestamps")
    void resolvedAtBeforeOpenedAtRejected() {
        UUID itemId = insertReconciliationItem();
        UUID caseId = findCaseIdByItemId(itemId);
        UUID ops = insertTestUser("OPS");

        assertThatThrownBy(() ->
                jdbc.update("UPDATE reconciliation_cases SET status = 'RESOLVED', resolved_by_user_id = ?, " +
                            "resolution_action = 'MANUAL_REVIEW_COMPLETED', resolution_note = 'Investigated', " +
                            "resolved_at = opened_at - INTERVAL '1 minute', updated_at = NOW() WHERE id = ?", ops, caseId)
        ).hasMessageContaining("chk_recon_cases_timestamps");
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private UUID insertRunningRun() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", id);
        return id;
    }

    private UUID insertReconciliationItem() {
        UUID runId = insertRunningRun();
        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'SNAPSHOT_CONSISTENCY', 'SNAPSHOT_MISMATCH', 'LEDGER_ACCOUNT', ?, 'desc', NOW())",
                itemId, runId, UUID.randomUUID());
        return itemId;
    }

    private UUID findCaseIdByItemId(UUID itemId) {
        return jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
    }

    private UUID insertTestUser(String role) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, '$2a$10$hash', ?, 'ACTIVE', NOW(), NOW())",
                userId, "user." + userId + "@example.com", role);
        return userId;
    }
}
