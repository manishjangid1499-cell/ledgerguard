package com.ledgerguard.refund.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable business record representing a successful partial or full payment refund.
 */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "initiated_by_user_id", nullable = false, updatable = false)
    private UUID initiatedByUserId;

    @Column(name = "refund_amount_minor", nullable = false, updatable = false)
    private long refundAmountMinor;

    @Column(name = "merchant_debit_amount_minor", nullable = false, updatable = false)
    private long merchantDebitAmountMinor;

    @Column(name = "fee_debit_amount_minor", nullable = false, updatable = false)
    private long feeDebitAmountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(Types.CHAR)
    private String currency;

    @Column(name = "journal_transaction_id", nullable = false, updatable = false, unique = true)
    private UUID journalTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Refund() {
        // JPA protected no-arg constructor
    }

    public Refund(
            UUID id,
            UUID paymentId,
            UUID initiatedByUserId,
            long refundAmountMinor,
            long merchantDebitAmountMinor,
            long feeDebitAmountMinor,
            String currency,
            UUID journalTransactionId,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        this.initiatedByUserId = Objects.requireNonNull(initiatedByUserId, "initiatedByUserId must not be null");
        this.refundAmountMinor = refundAmountMinor;
        this.merchantDebitAmountMinor = merchantDebitAmountMinor;
        this.feeDebitAmountMinor = feeDebitAmountMinor;
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.journalTransactionId = Objects.requireNonNull(journalTransactionId, "journalTransactionId must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (refundAmountMinor <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (merchantDebitAmountMinor < 0 || feeDebitAmountMinor < 0) {
            throw new IllegalArgumentException("Debit components cannot be negative");
        }
        if (refundAmountMinor != merchantDebitAmountMinor + feeDebitAmountMinor) {
            throw new IllegalArgumentException("Refund amount must equal merchant debit plus fee debit");
        }
    }

    public static Refund create(
            UUID id,
            UUID paymentId,
            UUID initiatedByUserId,
            long refundAmountMinor,
            long merchantDebitAmountMinor,
            long feeDebitAmountMinor,
            String currency,
            UUID journalTransactionId,
            Instant createdAt
    ) {
        return new Refund(
                id,
                paymentId,
                initiatedByUserId,
                refundAmountMinor,
                merchantDebitAmountMinor,
                feeDebitAmountMinor,
                currency,
                journalTransactionId,
                createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getInitiatedByUserId() {
        return initiatedByUserId;
    }

    public long getRefundAmountMinor() {
        return refundAmountMinor;
    }

    public long getMerchantDebitAmountMinor() {
        return merchantDebitAmountMinor;
    }

    public long getFeeDebitAmountMinor() {
        return feeDebitAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getJournalTransactionId() {
        return journalTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
