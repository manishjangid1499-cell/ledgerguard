package com.ledgerguard.payout.domain;

/**
 * Lifecycle states of an external PayoutOperation in Phase 23.
 */
public enum PayoutStatus {
    /**
     * Initial durable state with reserved BalanceHold committed prior to outbound provider submission.
     */
    CREATED,

    /**
     * Provider submission initiated, or confirmed actively in-flight with the provider; BalanceHold is ACTIVE.
     */
    PROCESSING,

    /**
     * Outbound request timed out or network outcome ambiguous; BalanceHold is ACTIVE; pending status recovery.
     */
    UNKNOWN,

    /**
     * Automated status recovery exhausted or contradictory provider identity detected; BalanceHold is ACTIVE.
     */
    RECONCILIATION_REQUIRED,

    /**
     * Provider confirmed DEBIT; double-entry journal committed; BalanceHold CONSUMED.
     */
    SUCCEEDED,

    /**
     * Definite provider rejection/failure (BalanceHold RELEASED) or unused reservation expired before submission (BalanceHold EXPIRED).
     */
    FAILED
}
