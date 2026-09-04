package com.ledgerguard.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable entity representing operational review and resolution of a single reconciliation item.
 * <p>
 * Separates immutable detection evidence (in {@code reconciliation_items}) from mutable operational
 * workflow state (assignment, investigation, resolution action).
 * <p>
 * Invariants:
 * <ul>
 *   <li>{@code OPEN}: assigned and resolved actors/timestamps/actions are null</li>
 *   <li>{@code IN_REVIEW}: assigned actor is non-null; cannot be reassigned once claimed</li>
 *   <li>{@code RESOLVED}: resolved actor, timestamp, and action are non-null; terminal & immutable</li>
 * </ul>
 */
@Entity
@Table(name = "reconciliation_cases")
public class ReconciliationCase {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reconciliation_item_id", nullable = false, updatable = false, unique = true)
    private UUID reconciliationItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReconciliationCaseStatus status;

    @Column(name = "assigned_to_user_id")
    private UUID assignedToUserId;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_action", length = 64)
    private ReconciliationResolutionAction resolutionAction;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReconciliationCase() {
        // JPA requirement
    }

    public ReconciliationCase(UUID id,
                              UUID reconciliationItemId,
                              ReconciliationCaseStatus status,
                              UUID assignedToUserId,
                              UUID resolvedByUserId,
                              ReconciliationResolutionAction resolutionAction,
                              String resolutionNote,
                              Instant openedAt,
                              Instant updatedAt,
                              Instant resolvedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reconciliationItemId = Objects.requireNonNull(reconciliationItemId, "reconciliationItemId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.assignedToUserId = assignedToUserId;
        this.resolvedByUserId = resolvedByUserId;
        this.resolutionAction = resolutionAction;
        this.resolutionNote = resolutionNote;
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.resolvedAt = resolvedAt;
    }

    public static ReconciliationCase open(UUID itemId, Instant openedAt) {
        Instant now = openedAt != null ? openedAt : Instant.now();
        return new ReconciliationCase(
                UUID.randomUUID(),
                itemId,
                ReconciliationCaseStatus.OPEN,
                null,
                null,
                null,
                null,
                now,
                now,
                null
        );
    }

    /**
     * Claims the case for an operator.
     *
     * @param actorId the claiming operator's user ID
     */
    public void claim(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        if (this.status == ReconciliationCaseStatus.RESOLVED) {
            throw new IllegalStateException("Cannot claim terminal resolved case " + this.id);
        }
        if (this.status == ReconciliationCaseStatus.IN_REVIEW) {
            if (!actorId.equals(this.assignedToUserId)) {
                throw new IllegalStateException("Case " + this.id + " is already claimed by another operator");
            }
            return; // Idempotent replay for same operator
        }
        this.status = ReconciliationCaseStatus.IN_REVIEW;
        this.assignedToUserId = actorId;
        Instant now = Instant.now();
        this.updatedAt = now.isBefore(this.openedAt) ? this.openedAt : now;
    }

    /**
     * Resolves the case as SNAPSHOT_REPAIRED.
     */
    public void resolveSnapshotRepaired(UUID actorId) {
        verifyCanResolve(actorId);
        this.status = ReconciliationCaseStatus.RESOLVED;
        this.resolvedByUserId = actorId;
        this.resolutionAction = ReconciliationResolutionAction.SNAPSHOT_REPAIRED;
        Instant now = Instant.now();
        this.resolvedAt = now.isBefore(this.openedAt) ? this.openedAt : now;
        this.updatedAt = this.resolvedAt;
    }

    /**
     * Resolves the case as ALREADY_CONSISTENT.
     */
    public void resolveAlreadyConsistent(UUID actorId) {
        verifyCanResolve(actorId);
        this.status = ReconciliationCaseStatus.RESOLVED;
        this.resolvedByUserId = actorId;
        this.resolutionAction = ReconciliationResolutionAction.ALREADY_CONSISTENT;
        Instant now = Instant.now();
        this.resolvedAt = now.isBefore(this.openedAt) ? this.openedAt : now;
        this.updatedAt = this.resolvedAt;
    }

    /**
     * Resolves the case manually with an investigation note.
     */
    public void resolveManualReview(UUID actorId, String note) {
        verifyCanResolve(actorId);
        if (note == null || note.trim().isEmpty()) {
            throw new IllegalArgumentException("Resolution note must not be blank for manual review");
        }
        String normalizedNote = note.trim();
        if (normalizedNote.length() > 1000) {
            throw new IllegalArgumentException("Resolution note must not exceed 1000 characters");
        }
        this.status = ReconciliationCaseStatus.RESOLVED;
        this.resolvedByUserId = actorId;
        this.resolutionAction = ReconciliationResolutionAction.MANUAL_REVIEW_COMPLETED;
        this.resolutionNote = normalizedNote;
        Instant now = Instant.now();
        this.resolvedAt = now.isBefore(this.openedAt) ? this.openedAt : now;
        this.updatedAt = this.resolvedAt;
    }

    private void verifyCanResolve(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        if (this.status == ReconciliationCaseStatus.RESOLVED) {
            throw new IllegalStateException("Case " + this.id + " is already in terminal RESOLVED status");
        }
        if (this.status == ReconciliationCaseStatus.IN_REVIEW) {
            if (!actorId.equals(this.assignedToUserId)) {
                throw new IllegalStateException("Case " + this.id + " is claimed by another operator");
            }
        }
    }

    // Getters

    public UUID getId() {
        return id;
    }

    public UUID getReconciliationItemId() {
        return reconciliationItemId;
    }

    public ReconciliationCaseStatus getStatus() {
        return status;
    }

    public UUID getAssignedToUserId() {
        return assignedToUserId;
    }

    public UUID getResolvedByUserId() {
        return resolvedByUserId;
    }

    public ReconciliationResolutionAction getResolutionAction() {
        return resolutionAction;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
