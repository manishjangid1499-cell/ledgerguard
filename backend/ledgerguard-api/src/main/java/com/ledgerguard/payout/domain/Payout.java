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
        this.status = PayoutStatus.PROCESSING;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public void markSucceeded(UUID providerOperationId, UUID journalTransactionId, Instant completedAt) {
        if (this.status != PayoutStatus.PROCESSING) {
            throw new IllegalStateException("Cannot transition Payout from " + this.status + " to SUCCEEDED");
        }
        this.providerOperationId = Objects.requireNonNull(providerOperationId, "providerOperationId must not be null");
        this.journalTransactionId = Objects.requireNonNull(journalTransactionId, "journalTransactionId must not be null");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        this.status = PayoutStatus.SUCCEEDED;
    }

    public void markFailed(Instant completedAt) {
        if (this.status != PayoutStatus.PROCESSING) {
            throw new IllegalStateException("Cannot transition Payout from " + this.status + " to FAILED");
        }
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        this.status = PayoutStatus.FAILED;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
