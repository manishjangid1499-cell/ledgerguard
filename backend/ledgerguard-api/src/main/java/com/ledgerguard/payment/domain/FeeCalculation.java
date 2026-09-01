package com.ledgerguard.payment.domain;

/**
 * Value object holding calculated platform fee and merchant net amounts.
 */
public record FeeCalculation(
        long grossAmountMinor,
        long feeAmountMinor,
        long merchantNetAmountMinor,
        int feeBasisPoints
) {
    public FeeCalculation {
        if (grossAmountMinor <= 0) {
            throw new IllegalArgumentException("Gross amount must be positive");
        }
        if (feeAmountMinor < 0) {
            throw new IllegalArgumentException("Fee amount must be non-negative");
        }
        if (merchantNetAmountMinor <= 0) {
            throw new IllegalArgumentException("Merchant net amount must be positive");
        }
        if (merchantNetAmountMinor != (grossAmountMinor - feeAmountMinor)) {
            throw new IllegalArgumentException("Merchant net amount must equal gross minus fee");
        }
    }
}
