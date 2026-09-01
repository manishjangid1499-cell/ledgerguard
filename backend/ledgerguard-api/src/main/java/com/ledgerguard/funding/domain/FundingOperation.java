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
        this.status = FundingStatus.PROCESSING;
        this.providerOperationId = null;
        this.journalTransactionId = null;
        this.createdAt = Objects.requireNonNull(createdAt, "Created at timestamp must not be null");
        this.completedAt = null;
    }

    /**
     * Transitions this funding operation from PROCESSING to SUCCEEDED upon committed ledger settlement.
     *
     * @param providerOperationId confirmed external PSP operation ID
     * @param journalTransactionId committed double-entry journal transaction ID
     * @param completedAt completion timestamp
     */
    public void markSucceeded(UUID providerOperationId, UUID journalTransactionId, Instant completedAt) {
        if (this.status == FundingStatus.SUCCEEDED) {
            throw new IllegalStateException("FundingOperation " + id + " is already in terminal status SUCCEEDED");
        }
        this.status = FundingStatus.SUCCEEDED;
        this.providerOperationId = Objects.requireNonNull(providerOperationId, "Provider operation ID must not be null");
        this.journalTransactionId = Objects.requireNonNull(journalTransactionId, "Journal transaction ID must not be null");
        this.completedAt = Objects.requireNonNull(completedAt, "Completed at timestamp must not be null");
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
