package com.ledgerguard.audit;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.audit.application.AuditService;
import com.ledgerguard.audit.domain.AuditAction;
import com.ledgerguard.audit.domain.AuditTargetType;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.reconciliation.api.ReconciliationCaseResponse;
import com.ledgerguard.reconciliation.api.SnapshotRepairResponse;
import com.ledgerguard.reconciliation.application.ReconciliationCaseManagementService;
import com.ledgerguard.reconciliation.application.SnapshotAutoRepairService;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationConflictException;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationResolutionAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 28 â€” Audit Trail & Security Hardening Integration Tests")
class AuditTrailIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private ReconciliationCaseManagementService managementService;

    @Autowired
    private SnapshotAutoRepairService autoRepairService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User opsA;
    private User opsB;

    @BeforeEach
    void setUp() {
        opsA = userRepository.save(new User(UUID.randomUUID(), "opsAuditA." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
        opsB = userRepository.save(new User(UUID.randomUUID(), "opsAuditB." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
    }

    @Test
    @DisplayName("AuditService enforces Propagation.MANDATORY (fails outside transaction)")
    void auditServiceFailsOutsideTransaction() {
        assertThatThrownBy(() -> auditService.auditCaseClaimed(
                opsA.getId(), UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("No existing transaction found for transaction marked with propagation 'mandatory'");
    }

    @Test
    @DisplayName("AuditService has no public generic Map method")
    void noGenericPublicMapMethodOnAuditService() {
        for (Method method : AuditService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                for (Class<?> paramType : method.getParameterTypes()) {
                    assertThat(Map.class.isAssignableFrom(paramType))
                            .as("AuditService public method '%s' must not accept Map", method.getName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("Database-enforced append-only: UPDATE rejected by PostgreSQL trigger")
    void updateRejectedByDatabaseTrigger() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());

        UUID auditId = jdbc.queryForObject(
                "SELECT id FROM audit_events WHERE target_id = ?", UUID.class, caseId);
        assertThat(auditId).isNotNull();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_events SET action = 'RECONCILIATION_CASE_MANUALLY_RESOLVED' WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is append-only: UPDATE is strictly prohibited");

        // Verify row is unchanged
        String action = jdbc.queryForObject("SELECT action FROM audit_events WHERE id = ?", String.class, auditId);
        assertThat(action).isEqualTo("RECONCILIATION_CASE_CLAIMED");
    }

    @Test
    @DisplayName("Database-enforced append-only: DELETE rejected by PostgreSQL trigger")
    void deleteRejectedByDatabaseTrigger() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());

        UUID auditId = jdbc.queryForObject(
                "SELECT id FROM audit_events WHERE target_id = ?", UUID.class, caseId);
        assertThat(auditId).isNotNull();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is append-only: DELETE is strictly prohibited");

        // Verify row still exists
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE id = ?", Integer.class, auditId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Database-enforced append-only: TRUNCATE rejected by PostgreSQL trigger")
    void truncateRejectedByDatabaseTrigger() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());

        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_events"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_events is append-only: TRUNCATE is strictly prohibited");

        // Verify rows still exist
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE target_id = ?", Integer.class, caseId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Reconciliation case claim writes exactly one audit record with database timestamp")
    void claimWritesAuditRecordWithDbTimestamp() {
        Instant before = Instant.now().minusSeconds(5);
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");

        managementService.claimCase(caseId, opsA.getId());

        var rows = jdbc.queryForList("SELECT * FROM audit_events WHERE target_id = ?", caseId);
        assertThat(rows).hasSize(1);

        var row = rows.get(0);
        assertThat(row.get("actor_user_id")).isEqualTo(opsA.getId());
        assertThat(row.get("action")).isEqualTo("RECONCILIATION_CASE_CLAIMED");
        assertThat(row.get("target_type")).isEqualTo("RECONCILIATION_CASE");
        assertThat(row.get("target_id")).isEqualTo(caseId);

        Timestamp occurredAt = (Timestamp) row.get("occurred_at");
        assertThat(occurredAt).isNotNull();
        assertThat(occurredAt.toInstant()).isAfterOrEqualTo(before);
        assertThat(Duration.between(occurredAt.toInstant(), Instant.now()).abs().toSeconds()).isLessThan(30);

        String details = String.valueOf(row.get("details"));
        assertThat(details).contains("\"previous_status\": \"OPEN\"");
        assertThat(details).contains("\"new_status\": \"IN_REVIEW\"");
    }

    @Test
    @DisplayName("Idempotent claim replay writes 0 additional audit records")
    void claimReplayWritesZeroAdditionalAuditRecords() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");

        // First claim
        managementService.claimCase(caseId, opsA.getId());
        assertThat(countAuditRows(caseId)).isEqualTo(1);

        // Replay by same operator
        managementService.claimCase(caseId, opsA.getId());
        assertThat(countAuditRows(caseId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Manual resolution writes audit record without resolution_note in details")
    void manualResolutionAuditedWithoutNote() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_NOT_FOUND, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());
        assertThat(countAuditRows(caseId)).isEqualTo(1);

        String note = "Investigation concluded provider missing transaction";
        managementService.resolveManually(caseId, opsA.getId(), note);

        var rows = jdbc.queryForList(
                "SELECT * FROM audit_events WHERE target_id = ? AND action = 'RECONCILIATION_CASE_MANUALLY_RESOLVED'", caseId);
        assertThat(rows).hasSize(1);

        var row = rows.get(0);
        assertThat(row.get("actor_user_id")).isEqualTo(opsA.getId());
        String details = String.valueOf(row.get("details"));
        assertThat(details).contains("\"previous_status\": \"IN_REVIEW\"");
        assertThat(details).contains("\"new_status\": \"RESOLVED\"");
        assertThat(details).contains("\"resolution_action\": \"MANUAL_REVIEW_COMPLETED\"");
        assertThat(details).doesNotContain("resolution_note");
        assertThat(details).doesNotContain(note);

        // Replay with identical note
        managementService.resolveManually(caseId, opsA.getId(), note);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE target_id = ? AND action = 'RECONCILIATION_CASE_MANUALLY_RESOLVED'", Integer.class, caseId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Snapshot auto-repair writes RECONCILIATION_SNAPSHOT_REPAIRED audit record")
    void snapshotRepairAudited() {
        UUID accountId = seedLedgerAccountWithSnapshot(1000L);
        UUID caseId = seedSnapshotMismatchCase(accountId, 1000L, 2000L);

        SnapshotRepairResponse resp = autoRepairService.repairSnapshot(caseId, opsA.getId());
        assertThat(resp.resolutionAction()).isEqualTo("SNAPSHOT_REPAIRED");

        var rows = jdbc.queryForList(
                "SELECT * FROM audit_events WHERE target_id = ? AND action = 'RECONCILIATION_SNAPSHOT_REPAIRED'", caseId);
        assertThat(rows).hasSize(1);

        var row = rows.get(0);
        assertThat(row.get("actor_user_id")).isEqualTo(opsA.getId());
        assertThat(row.get("target_type")).isEqualTo("RECONCILIATION_CASE");
        String details = String.valueOf(row.get("details"));
        assertThat(details).contains("\"resolution_action\": \"SNAPSHOT_REPAIRED\"");
        assertThat(details).contains("\"problem_type\": \"SNAPSHOT_MISMATCH\"");
        assertThat(details).contains("\"entity_type\": \"LEDGER_ACCOUNT\"");
        assertThat(details).contains(accountId.toString());

        // Replay repair
        SnapshotRepairResponse replay = autoRepairService.repairSnapshot(caseId, opsA.getId());
        assertThat(replay.resolutionAction()).isEqualTo("SNAPSHOT_REPAIRED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE target_id = ? AND action = 'RECONCILIATION_SNAPSHOT_REPAIRED'", Integer.class, caseId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Already consistent repair writes RECONCILIATION_ALREADY_CONSISTENT audit record")
    void alreadyConsistentRepairAudited() {
        // Current snapshot matches journal truth (0)
        UUID accountId = seedLedgerAccountWithSnapshot(0L);
        UUID caseId = seedSnapshotMismatchCase(accountId, 0L, 0L);

        SnapshotRepairResponse resp = autoRepairService.repairSnapshot(caseId, opsA.getId());
        assertThat(resp.resolutionAction()).isEqualTo("ALREADY_CONSISTENT");

        var rows = jdbc.queryForList(
                "SELECT * FROM audit_events WHERE target_id = ? AND action = 'RECONCILIATION_ALREADY_CONSISTENT'", caseId);
        assertThat(rows).hasSize(1);

        var row = rows.get(0);
        String details = String.valueOf(row.get("details"));
        assertThat(details).contains("\"resolution_action\": \"ALREADY_CONSISTENT\"");

        // Replay repair
        autoRepairService.repairSnapshot(caseId, opsA.getId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE target_id = ? AND action = 'RECONCILIATION_ALREADY_CONSISTENT'", Integer.class, caseId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Audit insertion failure rolls back business case mutation")
    void auditFailureRollsBackCaseMutation() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");

        // Force an audit insertion failure inside transaction by referencing a non-existent actor UUID
        UUID nonExistentActor = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            managementService.claimCase(caseId, nonExistentActor);
            return null;
        })).isInstanceOf(DataAccessException.class);

        // Verify case mutation was rolled back to OPEN and unassigned
        String caseStatus = jdbc.queryForObject("SELECT status FROM reconciliation_cases WHERE id = ?", String.class, caseId);
        assertThat(caseStatus).isEqualTo("OPEN");
        UUID assignedTo = jdbc.queryForObject("SELECT assigned_to_user_id FROM reconciliation_cases WHERE id = ?", UUID.class, caseId);
        assertThat(assignedTo).isNull();

        // 0 audit records
        assertThat(countAuditRows(caseId)).isZero();
    }

    @Test
    @DisplayName("Audit failure during SNAPSHOT_REPAIRED rolls back snapshot balance, updated_at, case resolution, and commits 0 audit records")
    void auditFailureRollsBackSnapshotRepair() {
        long initialObserved = 1000L;
        long expectedReconstructed = 0L; // 0 journals posted, so expected truth is 0
        UUID accountId = seedLedgerAccountWithSnapshot(initialObserved);
        UUID caseId = seedSnapshotMismatchCase(accountId, initialObserved, expectedReconstructed);

        Timestamp initialUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Timestamp.class, accountId);
        assertThat(initialUpdatedAt).isNotNull();

        UUID nonExistentActor = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            autoRepairService.repairSnapshot(caseId, nonExistentActor);
            return null;
        })).isInstanceOf(DataAccessException.class);

        // 1. Snapshot balance remains at pre-repair value
        Long currentBalance = jdbc.queryForObject(
                "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, accountId);
        assertThat(currentBalance).isEqualTo(initialObserved);

        // 2. Snapshot updated_at timestamp remains unchanged
        Timestamp postAttemptUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Timestamp.class, accountId);
        assertThat(postAttemptUpdatedAt).isEqualTo(initialUpdatedAt);

        // 3. Reconciliation case remains in OPEN state, unresolved, unassigned
        String caseStatus = jdbc.queryForObject("SELECT status FROM reconciliation_cases WHERE id = ?", String.class, caseId);
        assertThat(caseStatus).isEqualTo("OPEN");
        String resolutionAction = jdbc.queryForObject("SELECT resolution_action FROM reconciliation_cases WHERE id = ?", String.class, caseId);
        assertThat(resolutionAction).isNull();

        // 4. Exactly 0 audit rows committed
        assertThat(countAuditRows(caseId)).isZero();
    }

    @Test
    @DisplayName("Audit failure during ALREADY_CONSISTENT rolls back case resolution and commits 0 audit records")
    void auditFailureRollsBackAlreadyConsistent() {
        // Truth is 0, snapshot is 0 -> ALREADY_CONSISTENT
        UUID accountId = seedLedgerAccountWithSnapshot(0L);
        UUID caseId = seedSnapshotMismatchCase(accountId, 0L, 0L);

        UUID nonExistentActor = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            autoRepairService.repairSnapshot(caseId, nonExistentActor);
            return null;
        })).isInstanceOf(DataAccessException.class);

        // Reconciliation case remains in OPEN state, unresolved
        String caseStatus = jdbc.queryForObject("SELECT status FROM reconciliation_cases WHERE id = ?", String.class, caseId);
        assertThat(caseStatus).isEqualTo("OPEN");
        String resolutionAction = jdbc.queryForObject("SELECT resolution_action FROM reconciliation_cases WHERE id = ?", String.class, caseId);
        assertThat(resolutionAction).isNull();

        // Exactly 0 audit rows committed
        assertThat(countAuditRows(caseId)).isZero();
    }

    @Test
    @DisplayName("Business validation/conflict failure writes 0 audit records")
    void businessFailureWritesZeroAuditRecords() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());
        assertThat(countAuditRows(caseId)).isEqualTo(1);

        // Competing claim by opsB fails with 409
        assertThatThrownBy(() -> managementService.claimCase(caseId, opsB.getId()))
                .isInstanceOf(ReconciliationConflictException.class);

        // Still only 1 audit row (from the initial opsA claim, 0 from failed opsB claim)
        assertThat(countAuditRows(caseId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent identical claim requests serialize via row locks to exactly 1 audit event")
    void concurrentClaimWritesExactlyOneAuditRecord() throws Exception {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Callable<ReconciliationCaseResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await();
                return managementService.claimCase(caseId, opsA.getId());
            });
        }

        List<Future<ReconciliationCaseResponse>> futures = new ArrayList<>();
        for (Callable<ReconciliationCaseResponse> task : tasks) {
            futures.add(executor.submit(task));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<ReconciliationCaseResponse> future : futures) {
            ReconciliationCaseResponse resp = future.get();
            assertThat(resp.status()).isEqualTo("IN_REVIEW");
            assertThat(resp.assignedToUserId()).isEqualTo(opsA.getId());
        }

        executor.shutdown();

        // Exactly one audit row committed
        assertThat(countAuditRows(caseId)).isEqualTo(1);
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private int countAuditRows(UUID caseId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE target_id = ?", Integer.class, caseId);
    }

    private UUID seedCase(ReconciliationProblemType problemType, String entityType) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        UUID itemId = UUID.randomUUID();
        ReconciliationLevel level = switch (problemType) {
            case SNAPSHOT_MISMATCH, SNAPSHOT_MISSING -> ReconciliationLevel.SNAPSHOT_CONSISTENCY;
            case UNBALANCED_JOURNAL, MALFORMED_JOURNAL -> ReconciliationLevel.JOURNAL_BALANCE;
            default -> ReconciliationLevel.PROVIDER_SETTLEMENT;
        };

        ReconciliationClassification classification = (problemType == ReconciliationProblemType.PROVIDER_UNAVAILABLE)
                ? ReconciliationClassification.UNRESOLVED : ReconciliationClassification.DISCREPANCY;

        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, description, detected_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'test desc', NOW())",
                itemId, runId, classification.name(), level.name(), problemType.name(), entityType, UUID.randomUUID());

        return jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
    }

    private UUID seedLedgerAccountWithSnapshot(long snapshotBalance) {
        User user = userRepository.save(new User(UUID.randomUUID(), "cust." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                    "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', NOW(), NOW())", accountId, user.getId());

        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = ?, updated_at = NOW() WHERE ledger_account_id = ?",
                snapshotBalance, accountId);

        return accountId;
    }

    private UUID seedSnapshotMismatchCase(UUID accountId, long observed, long expected) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, observed_local_status, expected_value, actual_value, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'SNAPSHOT_CONSISTENCY', 'SNAPSHOT_MISMATCH', 'LEDGER_ACCOUNT', ?, 'MISMATCH', ?, ?, 'Snapshot mismatch test', NOW())",
                itemId, runId, accountId, expected, observed);

        return jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
    }
}