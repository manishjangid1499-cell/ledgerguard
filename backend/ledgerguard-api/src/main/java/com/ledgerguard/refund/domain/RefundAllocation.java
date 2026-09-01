package com.ledgerguard.refund.domain;

/**
 * Result of a proportional refund allocation calculation.
 */
public record RefundAllocation(
        long refundAmountMinor,
        long merchantDebitAmountMinor,
        long feeDebitAmountMinor,
        String policyVersion
) {
    public RefundAllocation {
        if (refundAmountMinor <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (merchantDebitAmountMinor < 0) {
            throw new IllegalArgumentException("Merchant debit amount cannot be negative");
        }
        if (feeDebitAmountMinor < 0) {
            throw new IllegalArgumentException("Fee debit amount cannot be negative");
        }
        if (refundAmountMinor != merchantDebitAmountMinor + feeDebitAmountMinor) {
            throw new IllegalArgumentException("Refund amount must equal merchant debit plus fee debit");
        }
    }
}
