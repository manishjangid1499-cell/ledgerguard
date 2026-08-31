package com.ledgerguard.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing an immutable internal wallet transfer business record.
 * Tied 1-to-1 to an authoritative POSTED double-entry journal transaction.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "initiated_by_user_id", nullable = false, updatable = false)
    private UUID initiatedByUserId;

    @Column(name = "source_ledger_account_id", nullable = false, updatable = false)
    private UUID sourceLedgerAccountId;

    @Column(name = "destination_ledger_account_id", nullable = false, updatable = false)
    private UUID destinationLedgerAccountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "journal_transaction_id", nullable = false, unique = true, updatable = false)
    private UUID journalTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transfer() {
        // For JPA
    }

    public Transfer(UUID id,
                    UUID initiatedByUserId,
                    UUID sourceLedgerAccountId,
                    UUID destinationLedgerAccountId,
                    long amountMinor,
                    String currency,
                    UUID journalTransactionId,
                    Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Transfer ID must not be null");
        this.initiatedByUserId = Objects.requireNonNull(initiatedByUserId, "Initiated by user ID must not be null");
        this.sourceLedgerAccountId = Objects.requireNonNull(sourceLedgerAccountId, "Source ledger account ID must not be null");
        this.destinationLedgerAccountId = Objects.requireNonNull(destinationLedgerAccountId, "Destination ledger account ID must not be null");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount minor must be strictly positive: " + amountMinor);
        }
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "Currency must not be null");
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Currency must be INR: " + currency);
        }
        if (sourceLedgerAccountId.equals(destinationLedgerAccountId)) {
            throw new IllegalArgumentException("Source and destination ledger accounts must be distinct");
        }
        this.journalTransactionId = Objects.requireNonNull(journalTransactionId, "Journal transaction ID must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at timestamp must not be null");
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

    public UUID getDestinationLedgerAccountId() {
        return destinationLedgerAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
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
