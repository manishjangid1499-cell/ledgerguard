package com.ledgerguard.funding.domain;

/**
 * Lifecycle status of an external wallet funding operation in Phase 23.
 */
public enum FundingStatus {
    /**
     * Initial durable state committed prior to initiating outbound provider submission.
     */
    CREATED,

    /**
     * Provider submission initiated, or confirmed actively in-flight with the provider.
     */
    PROCESSING,

    /**
     * Outbound request timed out or network outcome ambiguous; pending automated status recovery.
     */
    UNKNOWN,

    /**
     * Automated status recovery exhausted or contradictory provider identity detected; awaiting reconciliation.
     */
    RECONCILIATION_REQUIRED,

    /**
     * Terminal state indicating verified provider confirmation and committed local ledger settlement.
     */
    SUCCEEDED,

    /**
     * Terminal state indicating verified provider failure or pre-provider local failure.
     */
    FAILED
}
