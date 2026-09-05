package com.ledgerguard.audit.application;

import tools.jackson.databind.ObjectMapper;
import com.ledgerguard.audit.domain.AuditAction;
import com.ledgerguard.audit.domain.AuditTargetType;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationResolutionAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuditService {

    private static final String INSERT_SQL = """
            INSERT INTO audit_events (id, actor_user_id, action, target_type, target_id, details)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void auditCaseClaimed(UUID actorUserId, UUID caseId) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previous_status", ReconciliationCaseStatus.OPEN.name());
        details.put("new_status", ReconciliationCaseStatus.IN_REVIEW.name());

        insert(actorUserId, AuditAction.RECONCILIATION_CASE_CLAIMED, AuditTargetType.RECONCILIATION_CASE, caseId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void auditSnapshotRepaired(UUID actorUserId, UUID caseId, UUID ledgerAccountId) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId must not be null");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resolution_action", ReconciliationResolutionAction.SNAPSHOT_REPAIRED.name());
        details.put("problem_type", ReconciliationProblemType.SNAPSHOT_MISMATCH.name());
        details.put("entity_type", "LEDGER_ACCOUNT");
        details.put("entity_id", ledgerAccountId.toString());

        insert(actorUserId, AuditAction.RECONCILIATION_SNAPSHOT_REPAIRED, AuditTargetType.RECONCILIATION_CASE, caseId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void auditSnapshotAlreadyConsistent(UUID actorUserId, UUID caseId, UUID ledgerAccountId) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId must not be null");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resolution_action", ReconciliationResolutionAction.ALREADY_CONSISTENT.name());
        details.put("problem_type", ReconciliationProblemType.SNAPSHOT_MISMATCH.name());
        details.put("entity_type", "LEDGER_ACCOUNT");
        details.put("entity_id", ledgerAccountId.toString());

        insert(actorUserId, AuditAction.RECONCILIATION_ALREADY_CONSISTENT, AuditTargetType.RECONCILIATION_CASE, caseId, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void auditCaseManuallyResolved(UUID actorUserId, UUID caseId, ReconciliationCaseStatus previousStatus) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previous_status", previousStatus.name());
        details.put("new_status", ReconciliationCaseStatus.RESOLVED.name());
        details.put("resolution_action", ReconciliationResolutionAction.MANUAL_REVIEW_COMPLETED.name());

        insert(actorUserId, AuditAction.RECONCILIATION_CASE_MANUALLY_RESOLVED, AuditTargetType.RECONCILIATION_CASE, caseId, details);
    }

    private void insert(UUID actorUserId,
                        AuditAction action,
                        AuditTargetType targetType,
                        UUID targetId,
                        Map<String, Object> details) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");

        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize internal audit details", e);
        }

        UUID eventId = UUID.randomUUID();
        // Note: occurred_at is omitted so PostgreSQL supplies DEFAULT NOW()
        jdbcTemplate.update(INSERT_SQL,
                eventId,
                actorUserId,
                action.name(),
                targetType.name(),
                targetId,
                detailsJson
        );
    }
}