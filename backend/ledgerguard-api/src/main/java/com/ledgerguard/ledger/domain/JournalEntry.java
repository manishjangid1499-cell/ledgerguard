package com.ledgerguard.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents an individual debit or credit entry within a journal transaction.
 * Entry amounts are strictly positive integer minor units (> 0). Direction determines debit vs credit.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_transaction_id", nullable = false)
    private JournalTransaction journalTransaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false)
    private LedgerAccount ledgerAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private EntryDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    protected JournalEntry() {
        // JPA requirement
    }

    public JournalEntry(UUID id, JournalTransaction journalTransaction, LedgerAccount ledgerAccount, EntryDirection direction, long amountMinor) {
        this.id = Objects.requireNonNull(id, "Entry ID must not be null");
        this.journalTransaction = Objects.requireNonNull(journalTransaction, "Journal transaction must not be null");
        this.ledgerAccount = Objects.requireNonNull(ledgerAccount, "Ledger account must not be null");
        this.direction = Objects.requireNonNull(direction, "Direction must not be null");

        if (amountMinor <= 0L) {
            throw new IllegalArgumentException("Journal entry amount must be strictly greater than zero. Provided: " + amountMinor);
        }

        this.amountMinor = amountMinor;
    }

    public static JournalEntry createDebit(JournalTransaction journalTransaction, LedgerAccount ledgerAccount, long amountMinor) {
        return new JournalEntry(UUID.randomUUID(), journalTransaction, ledgerAccount, EntryDirection.DEBIT, amountMinor);
    }

    public static JournalEntry createCredit(JournalTransaction journalTransaction, LedgerAccount ledgerAccount, long amountMinor) {
        return new JournalEntry(UUID.randomUUID(), journalTransaction, ledgerAccount, EntryDirection.CREDIT, amountMinor);
    }

    public UUID getId() {
        return id;
    }

    public JournalTransaction getJournalTransaction() {
        return journalTransaction;
    }

    public LedgerAccount getLedgerAccount() {
        return ledgerAccount;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Money getAmountMoney() {
        return Money.inr(amountMinor);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JournalEntry that = (JournalEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
