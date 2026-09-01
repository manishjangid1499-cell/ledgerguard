package com.ledgerguard.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Application-facing projection of a user's wallet.
 * Represents the union of a user-owned LedgerAccount, its derived posted balance snapshot,
 * active hold reservations, and computed available balance string.
 */
public record Wallet(
        UUID ledgerAccountId,
        UUID ownerUserId,
        AccountType accountType,
        String currency,
        AccountStatus status,
        Money balance,
        Money activeHoldAmount,
        String availableBalanceMinor
) {
    public Wallet {
        Objects.requireNonNull(ledgerAccountId, "Ledger account ID must not be null");
        Objects.requireNonNull(ownerUserId, "Owner user ID must not be null");
        Objects.requireNonNull(accountType, "Account type must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(balance, "Balance must not be null");
        Objects.requireNonNull(activeHoldAmount, "Active hold amount must not be null");
        Objects.requireNonNull(availableBalanceMinor, "Available balance must not be null");
    }

    public Wallet(
            UUID ledgerAccountId,
            UUID ownerUserId,
            AccountType accountType,
            String currency,
            AccountStatus status,
            Money balance
    ) {
        this(ledgerAccountId, ownerUserId, accountType, currency, status, balance, Money.ofMinor(0L, currency), String.valueOf(balance.getMinorUnits()));
    }
}
