package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.reconciliation.api.ReconciliationCaseResponse;
import com.ledgerguard.reconciliation.application.ReconciliationCaseManagementService;
import com.ledgerguard.reconciliation.application.ReconciliationCaseQueryService;
import com.ledgerguard.reconciliation.domain.ReconciliationCase;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationConflictException;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationResolutionAction;
import com.ledgerguard.reconciliation.domain.ReconciliationValidationException;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationCaseRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Reconciliation case lifecycle and claim ownership transitions")
class ReconciliationCaseLifecycleTest extends AbstractIntegrationTest {

    @Autowired
    private ReconciliationCaseManagementService managementService;

    @Autowired
    private ReconciliationCaseQueryService queryService;

    @Autowired
    private ReconciliationCaseRepository caseRepository;

    @Autowired
    private ReconciliationItemRepository itemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private User opsA;
    private User opsB;

    @BeforeEach
    void setUp() {
        opsA = userRepository.save(new User(UUID.randomUUID(), "opsA." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
        opsB = userRepository.save(new User(UUID.randomUUID(), "opsB." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
    }

    @Test
    @DisplayName("Claim OPEN case transitions to IN_REVIEW and assigns actor")
    void claimOpenCaseSuccess() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");

        ReconciliationCaseResponse resp = managementService.claimCase(caseId, opsA.getId());

        assertThat(resp.status()).isEqualTo("IN_REVIEW");
        assertThat(resp.assignedToUserId()).isEqualTo(opsA.getId());
    }

    @Test
    @DisplayName("Repeated claim by same actor is idempotent success")
    void claimCaseSameActorIdempotent() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());

        ReconciliationCaseResponse replay = managementService.claimCase(caseId, opsA.getId());

        assertThat(replay.status()).isEqualTo("IN_REVIEW");
        assertThat(replay.assignedToUserId()).isEqualTo(opsA.getId());
    }

    @Test
    @DisplayName("Claim by competing actor on IN_REVIEW case throws 409 Conflict")
    void claimCaseCompetingActorConflict() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());

        assertThatThrownBy(() -> managementService.claimCase(caseId, opsB.getId()))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("already claimed by another operator");
    }

    @Test
    @DisplayName("Claiming a RESOLVED case throws 409 Conflict")
    void claimResolvedCaseConflict() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_STATUS_MISMATCH, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());
        managementService.resolveManually(caseId, opsA.getId(), "Manual investigation complete");

        assertThatThrownBy(() -> managementService.claimCase(caseId, opsA.getId()))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("Cannot claim terminal resolved case");
    }

    @Test
    @DisplayName("Assigned operator can manually resolve case with note")
    void manualResolveInReviewSuccess() {
        UUID caseId = seedCase(ReconciliationProblemType.UNBALANCED_JOURNAL, "JOURNAL_TRANSACTION");
        managementService.claimCase(caseId, opsA.getId());

        ReconciliationCaseResponse resp = managementService.resolveManually(caseId, opsA.getId(), "Investigated root cause; flagged to finance");

        assertThat(resp.status()).isEqualTo("RESOLVED");
        assertThat(resp.resolvedByUserId()).isEqualTo(opsA.getId());
        assertThat(resp.resolutionAction()).isEqualTo("MANUAL_REVIEW_COMPLETED");
        assertThat(resp.resolutionNote()).isEqualTo("Investigated root cause; flagged to finance");
        assertThat(resp.resolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("Unassigned operator resolving claimed case throws 409 Conflict")
    void manualResolveByUnassignedActorConflict() {
        UUID caseId = seedCase(ReconciliationProblemType.UNBALANCED_JOURNAL, "JOURNAL_TRANSACTION");
        managementService.claimCase(caseId, opsA.getId());

        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsB.getId(), "Attempt by opsB"))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("claimed by another operator");
    }

    @Test
    @DisplayName("Manual resolution is strictly prohibited for SNAPSHOT_MISMATCH")
    void manualResolveSnapshotMismatchConflict() {
        UUID caseId = seedCase(ReconciliationProblemType.SNAPSHOT_MISMATCH, "LEDGER_ACCOUNT");
        managementService.claimCase(caseId, opsA.getId());

        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsA.getId(), "Attempting manual close"))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("SNAPSHOT_MISMATCH cannot be manually closed");
    }

    @Test
    @DisplayName("Blank resolution note throws 400 validation exception")
    void manualResolveBlankNoteValidationException() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_UNAVAILABLE, "PAYOUT");

        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsA.getId(), "   "))
                .isInstanceOf(ReconciliationValidationException.class)
                .hasMessageContaining("Resolution note must not be blank");
    }

    @Test
    @DisplayName("Resolution note exceeding 1000 characters throws 400 validation exception")
    void manualResolveOver1000CharsValidationException() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_UNAVAILABLE, "PAYOUT");
        String longNote = "a".repeat(1001);

        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsA.getId(), longNote))
                .isInstanceOf(ReconciliationValidationException.class)
                .hasMessageContaining("must not exceed 1000 characters");
    }

    @Test
    @DisplayName("Repeated manual resolution with identical note is idempotent success")
    void manualResolveIdempotentReplay() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_NOT_FOUND, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());
        managementService.resolveManually(caseId, opsA.getId(), "Verified provider logs");

        ReconciliationCaseResponse replay = managementService.resolveManually(caseId, opsA.getId(), "Verified provider logs");

        assertThat(replay.status()).isEqualTo("RESOLVED");
        assertThat(replay.resolutionNote()).isEqualTo("Verified provider logs");
    }

    @Test
    @DisplayName("Repeated manual resolution with conflicting note throws 409 Conflict")
    void manualResolveConflictingNoteConflict() {
        UUID caseId = seedCase(ReconciliationProblemType.PROVIDER_NOT_FOUND, "FUNDING_OPERATION");
        managementService.claimCase(caseId, opsA.getId());
        managementService.resolveManually(caseId, opsA.getId(), "Original note");

        assertThatThrownBy(() -> managementService.resolveManually(caseId, opsA.getId(), "Different note"))
                .isInstanceOf(ReconciliationConflictException.class)
                .hasMessageContaining("conflicting terminal action or note");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

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

        // V15 auto-create trigger inserts case automatically
        return jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
    }
}
