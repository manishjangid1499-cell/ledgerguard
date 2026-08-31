package com.ledgerguard.ledger.api;

import com.ledgerguard.ledger.domain.Wallet;

import java.util.UUID;

/**
 * Public response DTO for user wallet inspection.
 * minor-unit balances are serialized as decimal strings for JavaScript precision safety.
 */
public record WalletResponse(
        UUID ledgerAccountId,
        String accountType,
        String currency,
        String status,
        String balanceMinor
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.ledgerAccountId(),
                wallet.accountType().name(),
                wallet.currency(),
                wallet.status().name(),
                String.valueOf(wallet.balance().getMinorUnits())
        );
    }
}
