package com.ledgerguard.funding.domain;

/**
 * Lifecycle status of an external wallet funding operation.
 */
public enum FundingStatus {
    /**
     * Initial durable state representing funding request creation prior to authoritative settlement.
     */
    PROCESSING,

    /**
     * Terminal state indicating verified provider confirmation and committed local ledger settlement.
     */
    SUCCEEDED
}
