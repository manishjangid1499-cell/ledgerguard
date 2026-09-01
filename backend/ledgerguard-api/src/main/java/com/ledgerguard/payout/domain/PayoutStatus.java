package com.ledgerguard.payout.domain;

/**
 * Lifecycle states of an external PayoutOperation in Phase 21.
 * <p>
 * Exactly:
 * - PROCESSING: BalanceHold is ACTIVE; awaiting definitive provider outcome.
 * - SUCCEEDED: Provider confirmed DEBIT; double-entry journal committed; BalanceHold CONSUMED.
 * - FAILED: Definite provider rejection/failure; no journal posted; BalanceHold RELEASED.
 */
public enum PayoutStatus {
    PROCESSING,
    SUCCEEDED,
    FAILED
}
