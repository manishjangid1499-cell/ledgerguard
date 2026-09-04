package com.ledgerguard.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only evidence record of a single discrepancy or unresolved observation
 * produced by a reconciliation run.
 * <p>
 * Immutability is enforced by the V14 DB trigger. No UPDATE or DELETE is permitted.
 * Items may only be inserted while the parent run is RUNNING (enforced by the same trigger).
 */
@Entity
@Table(name = "reconciliation_items")
public class ReconciliationItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reconciliation_run_id", nullable = false, updatable = false)
    private UUID reconciliationRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 16, updatable = false)
    private ReconciliationClassification classification;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 32, updatable = false)
    private ReconciliationLevel level;

    @Enumerated(EnumType.STRING)
    @Column(name = "problem_type", nullable = false, length = 64, updatable = false)
    private ReconciliationProblemType problemType;

    @Column(name = "entity_type", nullable = false, length = 32, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "observed_local_status", length = 32, updatable = false)
    private String observedLocalStatus;

    @Column(name = "expected_value", precision = 0, updatable = false)
    private BigDecimal expectedValue;

    @Column(name = "actual_value", precision = 0, updatable = false)
    private BigDecimal actualValue;

    @Column(name = "provider_status", length = 32, updatable = false)
    private String providerStatus;

    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    protected ReconciliationItem() {
        // JPA
    }

    private ReconciliationItem(Builder builder) {
        this.id = UUID.randomUUID();
        this.reconciliationRunId = Objects.requireNonNull(builder.reconciliationRunId);
        this.classification = Objects.requireNonNull(builder.classification);
        this.level = Objects.requireNonNull(builder.level);
        this.problemType = Objects.requireNonNull(builder.problemType);
        this.entityType = Objects.requireNonNull(builder.entityType);
        this.entityId = Objects.requireNonNull(builder.entityId);
        this.observedLocalStatus = builder.observedLocalStatus;
        this.expectedValue = builder.expectedValue;
        this.actualValue = builder.actualValue;
        this.providerStatus = builder.providerStatus;
        this.description = Objects.requireNonNull(builder.description);
        this.detectedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReconciliationRunId() { return reconciliationRunId; }
    public ReconciliationClassification getClassification() { return classification; }
    public ReconciliationLevel getLevel() { return level; }
    public ReconciliationProblemType getProblemType() { return problemType; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getObservedLocalStatus() { return observedLocalStatus; }
    public BigDecimal getExpectedValue() { return expectedValue; }
    public BigDecimal getActualValue() { return actualValue; }
    public String getProviderStatus() { return providerStatus; }
    public String getDescription() { return description; }
    public Instant getDetectedAt() { return detectedAt; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private UUID reconciliationRunId;
        private ReconciliationClassification classification;
        private ReconciliationLevel level;
        private ReconciliationProblemType problemType;
        private String entityType;
        private UUID entityId;
        private String observedLocalStatus;
        private BigDecimal expectedValue;
        private BigDecimal actualValue;
        private String providerStatus;
        private String description;

        public Builder runId(UUID runId) { this.reconciliationRunId = runId; return this; }
        public Builder classification(ReconciliationClassification c) { this.classification = c; return this; }
        public Builder level(ReconciliationLevel l) { this.level = l; return this; }
        public Builder problemType(ReconciliationProblemType pt) { this.problemType = pt; return this; }
        public Builder entityType(String et) { this.entityType = et; return this; }
        public Builder entityId(UUID id) { this.entityId = id; return this; }
        public Builder observedLocalStatus(String s) { this.observedLocalStatus = s; return this; }
        public Builder expectedValue(BigDecimal v) { this.expectedValue = v; return this; }
        public Builder actualValue(BigDecimal v) { this.actualValue = v; return this; }
        public Builder providerStatus(String ps) { this.providerStatus = ps; return this; }
        public Builder description(String d) { this.description = d; return this; }

        public ReconciliationItem build() { return new ReconciliationItem(this); }
    }
}
