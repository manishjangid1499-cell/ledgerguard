package com.ledgerguard.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable entity representing a single reconciliation run execution.
 * <p>
 * Lifecycle: RUNNING → COMPLETED | RUNNING → FAILED.
 * Terminal runs are immutable (enforced by V14 DB trigger).
 * Summary counters (discrepancy_count, unresolved_count) are derived from
 * persisted reconciliation_items at finalization — never from in-memory counters.
 */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReconciliationRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 32, updatable = false)
    private ReconciliationTrigger triggerSource;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "journals_checked", nullable = false)
    private long journalsChecked = 0;

    @Column(name = "accounts_checked", nullable = false)
    private long accountsChecked = 0;

    @Column(name = "operations_checked", nullable = false)
    private long operationsChecked = 0;

    @Column(name = "discrepancy_count", nullable = false)
    private long discrepancyCount = 0;

    @Column(name = "unresolved_count", nullable = false)
    private long unresolvedCount = 0;

    @Column(name = "failure_reason")
    private String failureReason;

    protected ReconciliationRun() {
        // JPA
    }

    public static ReconciliationRun start(ReconciliationTrigger trigger) {
        ReconciliationRun run = new ReconciliationRun();
        run.id = UUID.randomUUID();
        run.status = ReconciliationRunStatus.RUNNING;
        run.triggerSource = trigger;
        run.startedAt = Instant.now();
        return run;
    }

    public UUID getId() { return id; }
    public ReconciliationRunStatus getStatus() { return status; }
    public ReconciliationTrigger getTriggerSource() { return triggerSource; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public long getJournalsChecked() { return journalsChecked; }
    public long getAccountsChecked() { return accountsChecked; }
    public long getOperationsChecked() { return operationsChecked; }
    public long getDiscrepancyCount() { return discrepancyCount; }
    public long getUnresolvedCount() { return unresolvedCount; }
    public String getFailureReason() { return failureReason; }

    public void complete(long journalsChecked, long accountsChecked, long operationsChecked,
                         long discrepancyCount, long unresolvedCount) {
        this.status = ReconciliationRunStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.journalsChecked = journalsChecked;
        this.accountsChecked = accountsChecked;
        this.operationsChecked = operationsChecked;
        this.discrepancyCount = discrepancyCount;
        this.unresolvedCount = unresolvedCount;
    }

    public void fail(long journalsChecked, long accountsChecked, long operationsChecked,
                     long discrepancyCount, long unresolvedCount, String reason) {
        this.status = ReconciliationRunStatus.FAILED;
        this.completedAt = Instant.now();
        this.journalsChecked = journalsChecked;
        this.accountsChecked = accountsChecked;
        this.operationsChecked = operationsChecked;
        this.discrepancyCount = discrepancyCount;
        this.unresolvedCount = unresolvedCount;
        this.failureReason = reason;
    }
}
