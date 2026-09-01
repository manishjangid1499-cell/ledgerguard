package com.ledgerguard.hold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable reservation entity representing a temporary hold on a user's wallet balance.
 * A hold does not move money in the double-entry journal, but reduces available spendable balance.
 */
@Entity
@Table(name = "balance_holds")
public class BalanceHold {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ledger_account_id", nullable = false, updatable = false)
    private UUID ledgerAccountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(Types.CHAR)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    protected BalanceHold() {
        // JPA requirement
    }

    private BalanceHold(
            UUID id,
            UUID ledgerAccountId,
            long amountMinor,
            String currency,
            HoldStatus status,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt,
            Instant terminalAt
    ) {
        this.id = Objects.requireNonNull(id, "ID must not be null");
        this.ledgerAccountId = Objects.requireNonNull(ledgerAccountId, "Ledger account ID must not be null");
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "Currency must not be null");
        this.status = Objects.requireNonNull(status, "Status must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "Expires at must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at must not be null");
        this.terminalAt = terminalAt;
    }

    public static BalanceHold create(
            UUID id,
            UUID ledgerAccountId,
            long amountMinor,
            String currency,
            Instant expiresAt,
            Instant createdAt
    ) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Hold amount must be strictly positive: " + amountMinor);
        }
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Hold currency must be INR: " + currency);
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Expires at must be strictly after created at");
        }

        return new BalanceHold(
                id,
                ledgerAccountId,
                amountMinor,
                currency,
                HoldStatus.ACTIVE,
                expiresAt,
                createdAt,
                createdAt,
                null
        );
    }

    public void release(Instant terminalAt) {
        ensureActive();
        Objects.requireNonNull(terminalAt, "Terminal at timestamp must not be null");
        if (terminalAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Terminal at must not be before created at");
        }
        this.status = HoldStatus.RELEASED;
        this.terminalAt = terminalAt;
        this.updatedAt = terminalAt;
    }

    public void consume(Instant terminalAt) {
        ensureActive();
        Objects.requireNonNull(terminalAt, "Terminal at timestamp must not be null");
        if (terminalAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Terminal at must not be before created at");
        }
        this.status = HoldStatus.CONSUMED;
        this.terminalAt = terminalAt;
        this.updatedAt = terminalAt;
    }

    public void expire(Instant terminalAt) {
        ensureActive();
        Objects.requireNonNull(terminalAt, "Terminal at timestamp must not be null");
        if (terminalAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Terminal at must not be before created at");
        }
        this.status = HoldStatus.EXPIRED;
        this.terminalAt = terminalAt;
        this.updatedAt = terminalAt;
    }

    private void ensureActive() {
        if (this.status != HoldStatus.ACTIVE) {
            throw new IllegalStateException("Hold " + this.id + " is in terminal status " + this.status + " and cannot be transitioned");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getTerminalAt() {
        return terminalAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BalanceHold that = (BalanceHold) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
