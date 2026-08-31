package com.ledgerguard.ledger.domain;

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
 * Represents a ledger account in LedgerGuard.
 * Immutable financial history is stored in journal entries; ledger accounts contain NO mutable balance fields.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    private UUID id;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LedgerAccount() {
        // JPA requirement
    }

    public LedgerAccount(UUID id, UUID ownerUserId, AccountType accountType, String currency, AccountStatus status, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "Account ID must not be null");
        this.accountType = Objects.requireNonNull(accountType, "Account type must not be null");
        this.currency = Objects.requireNonNull(currency, "Currency must not be null");
        this.status = Objects.requireNonNull(status, "Status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Created at must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Updated at must not be null");

        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Ledger currency must be INR");
        }

        if (accountType.isSystemAccount()) {
            if (ownerUserId != null) {
                throw new IllegalArgumentException("System account of type " + accountType + " must not have an owner user ID");
            }
        } else {
            if (ownerUserId == null) {
                throw new IllegalArgumentException("User account of type " + accountType + " requires an owner user ID");
            }
        }

        this.ownerUserId = ownerUserId;
    }

    public static LedgerAccount createCustomerAccount(UUID ownerUserId) {
        Instant now = Instant.now();
        return new LedgerAccount(UUID.randomUUID(), ownerUserId, AccountType.CUSTOMER, "INR", AccountStatus.ACTIVE, now, now);
    }

    public static LedgerAccount createMerchantAccount(UUID ownerUserId) {
        Instant now = Instant.now();
        return new LedgerAccount(UUID.randomUUID(), ownerUserId, AccountType.MERCHANT, "INR", AccountStatus.ACTIVE, now, now);
    }

    public static LedgerAccount createSystemAccount(AccountType accountType) {
        if (!accountType.isSystemAccount()) {
            throw new IllegalArgumentException("Cannot create system account with non-system type: " + accountType);
        }
        Instant now = Instant.now();
        return new LedgerAccount(UUID.randomUUID(), null, accountType, "INR", AccountStatus.ACTIVE, now, now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public NormalBalance getNormalBalance() {
        return accountType.getNormalBalance();
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void close(Instant closedAt) {
        this.status = AccountStatus.CLOSED;
        this.updatedAt = Objects.requireNonNull(closedAt, "Closed at timestamp must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LedgerAccount that = (LedgerAccount) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
