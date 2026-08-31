package com.ledgerguard.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Application-facing projection of a user's wallet.
 * Represents the union of a user-owned LedgerAccount and its derived balance snapshot.
 */
public record Wallet(
        UUID ledgerAccountId,
        UUID ownerUserId,
        AccountType accountType,
        String currency,
        AccountStatus status,
        Money balance
) {
    public Wallet {
        Objects.requireNonNull(ledgerAccountId, "Ledger account ID must not be null");
        Objects.requireNonNull(ownerUserId, "Owner user ID must not be null");
        Objects.requireNonNull(accountType, "Account type must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(balance, "Balance must not be null");
    }
}
