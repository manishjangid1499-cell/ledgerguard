package com.ledgerguard.payment.domain;

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
 * JPA entity representing an internal merchant payment business record.
 * Follows an explicit state machine: CREATED -> PROCESSING -> SUCCEEDED / FAILED.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_user_id", nullable = false, updatable = false)
    private UUID customerUserId;

    @Column(name = "customer_ledger_account_id", nullable = false, updatable = false)
    private UUID customerLedgerAccountId;

    @Column(name = "merchant_ledger_account_id", nullable = false, updatable = false)
    private UUID merchantLedgerAccountId;

    @Column(name = "gross_amount_minor", nullable = false, updatable = false)
    private long grossAmountMinor;

    @Column(name = "fee_amount_minor", nullable = false, updatable = false)
    private long feeAmountMinor;

    @Column(name = "merchant_net_amount_minor", nullable = false, updatable = false)
    private long merchantNetAmountMinor;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "journal_transaction_id", unique = true)
    private UUID journalTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Payment() {
        // JPA required default constructor
    }

    public Payment(
            UUID id,
            UUID customerUserId,
            UUID customerLedgerAccountId,
            UUID merchantLedgerAccountId,
            long grossAmountMinor,
            long feeAmountMinor,
            long merchantNetAmountMinor,
            String currency,
            PaymentStatus status,
            UUID journalTransactionId,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.customerUserId = Objects.requireNonNull(customerUserId, "customerUserId must not be null");
        this.customerLedgerAccountId = Objects.requireNonNull(customerLedgerAccountId, "customerLedgerAccountId must not be null");
        this.merchantLedgerAccountId = Objects.requireNonNull(merchantLedgerAccountId, "merchantLedgerAccountId must not be null");
        this.grossAmountMinor = grossAmountMinor;
        this.feeAmountMinor = feeAmountMinor;
        this.merchantNetAmountMinor = merchantNetAmountMinor;
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.journalTransactionId = journalTransactionId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.completedAt = completedAt;
    }

    public static Payment create(
            UUID id,
            UUID customerUserId,
            UUID customerLedgerAccountId,
            UUID merchantLedgerAccountId,
            long grossAmountMinor,
            long feeAmountMinor,
            long merchantNetAmountMinor,
            String currency,
            Instant now
    ) {
        Objects.requireNonNull(now, "now must not be null");
        if (grossAmountMinor <= 0) {
            throw new IllegalArgumentException("Gross amount must be positive");
        }
        if (feeAmountMinor < 0) {
            throw new IllegalArgumentException("Fee amount must be non-negative");
        }
        if (merchantNetAmountMinor <= 0) {
            throw new IllegalArgumentException("Merchant net amount must be positive");
        }
        if (merchantNetAmountMinor != (grossAmountMinor - feeAmountMinor)) {
            throw new IllegalArgumentException("Merchant net amount must equal gross amount minus fee amount");
        }

        return new Payment(
                id,
                customerUserId,
                customerLedgerAccountId,
                merchantLedgerAccountId,
                grossAmountMinor,
                feeAmountMinor,
                merchantNetAmountMinor,
                currency,
                PaymentStatus.CREATED,
                null,
                now,
                now,
                null
        );
    }

    public void markProcessing(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (this.status != PaymentStatus.CREATED) {
            throw new IllegalStateException("Cannot transition to PROCESSING from status " + this.status);
        }
        this.status = PaymentStatus.PROCESSING;
        this.updatedAt = now;
    }

    public void markSucceeded(UUID journalTransactionId, Instant now) {
        Objects.requireNonNull(journalTransactionId, "journalTransactionId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (this.status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("Cannot transition to SUCCEEDED from status " + this.status);
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.journalTransactionId = journalTransactionId;
        this.updatedAt = now;
        this.completedAt = now;
    }

    public void markFailed(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (this.status != PaymentStatus.CREATED && this.status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("Cannot transition to FAILED from status " + this.status);
        }
        this.status = PaymentStatus.FAILED;
        this.updatedAt = now;
        this.completedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerUserId() {
        return customerUserId;
    }

    public UUID getCustomerLedgerAccountId() {
        return customerLedgerAccountId;
    }

    public UUID getMerchantLedgerAccountId() {
        return merchantLedgerAccountId;
    }

    public long getGrossAmountMinor() {
        return grossAmountMinor;
    }

    public long getFeeAmountMinor() {
        return feeAmountMinor;
    }

    public long getMerchantNetAmountMinor() {
        return merchantNetAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public UUID getJournalTransactionId() {
        return journalTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
