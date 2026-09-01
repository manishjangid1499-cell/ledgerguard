package com.ledgerguard.ledger.infrastructure;

import java.util.UUID;

/**
 * Coherent database projection combining the posted balance snapshot and
 * the sum of active balance holds in a single atomic SQL statement.
 */
public interface WalletBalanceProjection {
    UUID getLedgerAccountId();
    Long getPostedBalanceMinor();
    Number getActiveHoldAmountMinor();
}
