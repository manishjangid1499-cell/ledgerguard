package com.ledgerguard.funding.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable entity representing an external customer wallet funding operation.
 * <p>
 * The entity ID itself serves as the stable external PSP clientOperationId across retries.
 */
@Entity
@Table(name = "funding_operations")
public class FundingOperation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "initiated_by_user_id", nullable = false, updatable = false)
    private UUID initiatedByUserId;

    @Column(name = "customer_ledger_account_id", nullable = false, updatable = false)
    private UUID customerLedgerAccountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FundingStatus status;

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

    protected FundingOperation() {
        // JPA required default constructor
    }

    public FundingOperation(
            UUID id,
            UUID initiatedByUserId,
            UUID customerLedgerAccountId,
            long amountMinor,
            String currency,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "Funding ID must not be null");
        this.initiatedByUserId = Objects.requireNonNull(initiatedByUserId, "Initiated by user ID must not be null");
        this.customerLedgerAccountId = Objects.requireNonNull(customerLedgerAccountId, "Customer ledger account ID must not be null");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount minor must be strictly positive: " + amountMinor);
        }
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "Currency must not be null");
        this.status = FundingStatus.CREATED;
        this.providerOperationId = null;
        this.journalTransactionId = null;
        this.providerPollAttempts = 0;
        this.nextProviderPollAt = null;
        this.unknownSince = null;
        this.createdAt = Objects.requireNonNull(createdAt, "Created at timestamp must not be null");
        this.completedAt = null;
    }

    /**
     * Atomically claims this operation for submission, transitioning CREATED -> PROCESSING.
     */
    public void prepareSubmission(Instant nextPollAt) {
        if (this.status != FundingStatus.CREATED) {
            throw new IllegalStateException("Cannot prepare submission for FundingOperation " + id + " in status " + status);
        }
        this.status = FundingStatus.PROCESSING;
        this.nextProviderPollAt = Objects.requireNonNull(nextPollAt, "nextProviderPollAt must not be null");
    }

    /**
     * Transitions PROCESSING -> UNKNOWN upon network timeout or lost response.
     */
    public void markUnknown(Instant now, Instant nextPollAt) {
        if (this.status != FundingStatus.PROCESSING && this.status != FundingStatus.UNKNOWN) {
            throw new IllegalStateException("Cannot mark FundingOperation " + id + " UNKNOWN from status " + status);
        }
        this.status = FundingStatus.UNKNOWN;
        if (this.unknownSince == null) {
            this.unknownSince = Objects.requireNonNull(now, "unknownSince must not be null");
        }
        this.nextProviderPollAt = Objects.requireNonNull(nextPollAt, "nextProviderPollAt must not be null");
    }

    /**
     * Updates/retains PROCESSING state during recovery or provider confirmation.
     */
    public void markProcessing(Instant nextPollAt) {
        if (this.status != FundingStatus.PROCESSING && this.status != FundingStatus.UNKNOWN) {
            throw new IllegalStateException("Cannot mark FundingOperation " + id + " PROCESSING from status " + status);
        }
        this.status = FundingStatus.PROCESSING;
        this.nextProviderPollAt = Objects.requireNonNull(nextPollAt, "nextProviderPollAt must not be null");
    }

    /**
     * Transitions nonterminal operation to RECONCILIATION_REQUIRED upon max attempts or contradiction.
     */
    public void markReconciliationRequired() {
        if (this.status != FundingStatus.PROCESSING && this.status != FundingStatus.UNKNOWN) {
            throw new IllegalStateException("Cannot mark FundingOperation " + id + " RECONCILIATION_REQUIRED from status " + status);
        }
        this.status = FundingStatus.RECONCILIATION_REQUIRED;
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
                    "Cannot modify providerOperationId on FundingOperation " + id + " from "
                            + this.providerOperationId + " to " + providerOperationId);
        }
        this.providerOperationId = providerOperationId;
    }

    /**
     * Transitions this funding operation to FAILED.
     */
    public void markFailed(Instant completedAt, UUID providerOperationId) {
        if (this.status == FundingStatus.SUCCEEDED || this.status == FundingStatus.FAILED) {
            throw new IllegalStateException("FundingOperation " + id + " is already in terminal status " + status);
        }
        if (this.status == FundingStatus.CREATED && providerOperationId != null) {
            throw new IllegalStateException("FundingOperation " + id + " failing from CREATED must have providerOperationId null");
        }
        if ((this.status == FundingStatus.UNKNOWN || this.status == FundingStatus.RECONCILIATION_REQUIRED) && providerOperationId == null) {
            throw new IllegalStateException("FundingOperation " + id + " failing from " + status + " must have providerOperationId non-null");
        }
        bindProviderOperationId(providerOperationId);
        this.status = FundingStatus.FAILED;
        this.completedAt = Objects.requireNonNull(completedAt, "Completed at timestamp must not be null");
        this.nextProviderPollAt = null;
    }

    /**
     * Transitions this funding operation to SUCCEEDED upon committed ledger settlement.
     */
    public void markSucceeded(UUID providerOperationId, UUID journalTransactionId, Instant completedAt) {
        if (this.status == FundingStatus.SUCCEEDED) {
            throw new IllegalStateException("FundingOperation " + id + " is already in terminal status SUCCEEDED");
        }
        if (this.status == FundingStatus.FAILED) {
            throw new IllegalStateException("FundingOperation " + id + " is already in terminal status FAILED and cannot become SUCCEEDED");
        }
        bindProviderOperationId(providerOperationId);
        this.status = FundingStatus.SUCCEEDED;
        this.journalTransactionId = Objects.requireNonNull(journalTransactionId, "Journal transaction ID must not be null");
        this.completedAt = Objects.requireNonNull(completedAt, "Completed at timestamp must not be null");
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

    public UUID getCustomerLedgerAccountId() {
        return customerLedgerAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public FundingStatus getStatus() {
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
