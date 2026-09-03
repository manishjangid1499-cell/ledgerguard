package com.ledgerguard.payout.domain;

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
 * Durable persistence entity representing an external wallet withdrawal (Payout).
 * <p>
 * Backed by a dedicated BalanceHold to reserve funds during the in-flight provider interaction.
 */
@Entity
@Table(name = "payouts")
public class Payout {

    @Id
    private UUID id;

    @Column(name = "initiated_by_user_id", nullable = false, updatable = false)
    private UUID initiatedByUserId;

    @Column(name = "source_ledger_account_id", nullable = false, updatable = false)
    private UUID sourceLedgerAccountId;

    @Column(name = "balance_hold_id", nullable = false, unique = true, updatable = false)
    private UUID balanceHoldId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PayoutStatus status;

    @Column(name = "provider_operation_id", unique = true)
    private UUID providerOperationId;

    @Column(name = "journal_transaction_id", unique = true)
    private UUID journalTransactionId;

    @Column(name = "provider_poll_attempts", nullable = false)
    private int providerPollAttempts = 0;

    @Column(name = "next_provider_poll_at")
    private Instant nextProviderPollAt;

    @Column(name = "unknown_since")
    private Instant unknownSince;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Payout() {
        // JPA standard constructor
    }

    public Payout(
            UUID id,
            UUID initiatedByUserId,
            UUID sourceLedgerAccountId,
            UUID balanceHoldId,
            long amountMinor,
            String currency,
            Instant createdAt
    ) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Payout amountMinor must be strictly positive");
        }
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Payout currency must be INR");
        }
        this.id = Objects.requireNonNull(id, "Payout id must not be null");
        this.initiatedByUserId = Objects.requireNonNull(initiatedByUserId, "initiatedByUserId must not be null");
        this.sourceLedgerAccountId = Objects.requireNonNull(sourceLedgerAccountId, "sourceLedgerAccountId must not be null");
        this.balanceHoldId = Objects.requireNonNull(balanceHoldId, "balanceHoldId must not be null");
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = PayoutStatus.CREATED;
        this.providerOperationId = null;
        this.journalTransactionId = null;
        this.providerPollAttempts = 0;
        this.nextProviderPollAt = null;
        this.unknownSince = null;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.completedAt = null;
    }

    /**
     * Atomically claims this operation for submission, transitioning CREATED -> PROCESSING.
     */
    public void prepareSubmission(Instant nextPollAt) {
        if (this.status != PayoutStatus.CREATED) {
            throw new IllegalStateException("Cannot prepare submission for Payout " + id + " in status " + status);
        }
        this.status = PayoutStatus.PROCESSING;
        this.nextProviderPollAt = Objects.requireNonNull(nextPollAt, "nextProviderPollAt must not be null");
    }

    /**
     * Transitions PROCESSING -> UNKNOWN upon network timeout or lost response.
     */
    public void markUnknown(Instant now, Instant nextPollAt) {
        if (this.status != PayoutStatus.PROCESSING && this.status != PayoutStatus.UNKNOWN) {
            throw new IllegalStateException("Cannot mark Payout " + id + " UNKNOWN from status " + status);
        }
        this.status = PayoutStatus.UNKNOWN;
        if (this.unknownSince == null) {
            this.unknownSince = Objects.requireNonNull(now, "unknownSince must not be null");
        }
        this.nextProviderPollAt = Objects.requireNonNull(nextPollAt, "nextProviderPollAt must not be null");
    }

    /**
     * Updates/retains PROCESSING state during recovery or provider confirmation.
     */
    public void markProcessing(Instant nextPollAt) {
        if (this.status != PayoutStatus.PROCESSING && this.status != PayoutStatus.UNKNOWN) {
            throw new IllegalStateException("Cannot mark Payout " + id + " PROCESSING from status " + status);
        }
        this.status = PayoutStatus.PROCESSING;
        this.nextProviderPollAt = Objects.requireNonNull(nextPollAt, "nextProviderPollAt must not be null");
    }

    /**
     * Transitions nonterminal operation to RECONCILIATION_REQUIRED upon max attempts or contradiction.
     */
    public void markReconciliationRequired() {
        if (this.status != PayoutStatus.PROCESSING && this.status != PayoutStatus.UNKNOWN) {
            throw new IllegalStateException("Cannot mark Payout " + id + " RECONCILIATION_REQUIRED from status " + status);
        }
        this.status = PayoutStatus.RECONCILIATION_REQUIRED;
        this.nextProviderPollAt = null;
    }

    /**
     * Binds providerOperationId one-way under business row lock.
     */
    public void bindProviderOperationId(UUID providerOperationId) {
        if (providerOperationId == null) {
            return;
        }
        if (this.providerOperationId != null && !this.providerOperationId.equals(providerOperationId)) {
            throw new com.ledgerguard.provider.application.ProviderEventConflictException(
                    "Cannot modify providerOperationId on Payout " + id + " from "
                            + this.providerOperationId + " to " + providerOperationId);
        }
        this.providerOperationId = providerOperationId;
    }

    /**
     * Transitions this payout to FAILED.
     */
    public void markFailed(Instant completedAt, UUID providerOperationId) {
        if (this.status == PayoutStatus.SUCCEEDED || this.status == PayoutStatus.FAILED) {
            throw new IllegalStateException("Payout " + id + " is already in terminal status " + status);
        }
        if (this.status == PayoutStatus.CREATED && providerOperationId != null) {
            throw new IllegalStateException("Payout " + id + " failing from CREATED must have providerOperationId null");
        }
        if ((this.status == PayoutStatus.UNKNOWN || this.status == PayoutStatus.RECONCILIATION_REQUIRED) && providerOperationId == null) {
            throw new IllegalStateException("Payout " + id + " failing from " + status + " must have providerOperationId non-null");
        }
        bindProviderOperationId(providerOperationId);
        this.status = PayoutStatus.FAILED;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        this.nextProviderPollAt = null;
    }

    /**
     * Transitions this payout to SUCCEEDED upon committed ledger settlement.
     */
    public void markSucceeded(UUID providerOperationId, UUID journalTransactionId, Instant completedAt) {
        if (this.status == PayoutStatus.SUCCEEDED) {
            throw new IllegalStateException("Payout " + id + " is already in terminal status SUCCEEDED");
        }
        if (this.status == PayoutStatus.FAILED) {
            throw new IllegalStateException("Payout " + id + " is already in terminal status FAILED and cannot become SUCCEEDED");
        }
        bindProviderOperationId(providerOperationId);
        this.status = PayoutStatus.SUCCEEDED;
        this.journalTransactionId = Objects.requireNonNull(journalTransactionId, "journalTransactionId must not be null");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        this.nextProviderPollAt = null;
    }

    /**
     * Increments poll attempts during polling claim.
     */
    public void incrementPollAttempts(Instant nextPollAt) {
        this.providerPollAttempts++;
        this.nextProviderPollAt = nextPollAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInitiatedByUserId() {
        return initiatedByUserId;
    }

    public UUID getSourceLedgerAccountId() {
        return sourceLedgerAccountId;
    }

    public UUID getBalanceHoldId() {
        return balanceHoldId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public PayoutStatus getStatus() {
        return status;
    }

    public UUID getProviderOperationId() {
        return providerOperationId;
    }

    public UUID getJournalTransactionId() {
        return journalTransactionId;
    }

    public int getProviderPollAttempts() {
        return providerPollAttempts;
    }

    public Instant getNextProviderPollAt() {
        return nextProviderPollAt;
    }

    public Instant getUnknownSince() {
        return unknownSince;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
