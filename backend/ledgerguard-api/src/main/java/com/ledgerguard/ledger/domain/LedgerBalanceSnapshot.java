package com.ledgerguard.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Derived, transactionally maintained balance snapshot for a ledger account.
 * Note: The immutable journal remains the authoritative financial history;
 * this snapshot exists solely as a fast-read projection.
 */
@Entity
@Table(name = "ledger_balance_snapshots")
public class LedgerBalanceSnapshot {

    @Id
    @Column(name = "ledger_account_id")
    private UUID ledgerAccountId;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LedgerBalanceSnapshot() {
        // JPA requirement
    }

    public LedgerBalanceSnapshot(UUID ledgerAccountId, long balanceMinor, Instant updatedAt) {
        this.ledgerAccountId = Objects.requireNonNull(ledgerAccountId, "Ledger account ID must not be null");
        this.balanceMinor = balanceMinor;
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at timestamp must not be null");
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerBalanceSnapshot that = (LedgerBalanceSnapshot) o;
        return Objects.equals(ledgerAccountId, that.ledgerAccountId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ledgerAccountId);
    }
}
