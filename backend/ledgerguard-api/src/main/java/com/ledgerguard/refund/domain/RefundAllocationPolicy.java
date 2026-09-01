package com.ledgerguard.refund.domain;

import java.math.BigInteger;

/**
 * Deterministic proportional refund allocation policy.
 * Formula:
 * targetFee(R) = floor(originalFee * R / originalGross)
 * thisRefundFee = targetFee(before + requested) - targetFee(before)
 * thisRefundMerchant = requested - thisRefundFee
 *
 * Algorithm Version: original-payment-pro-rata:v1
 * Zero floating point representation; exact BigInteger integer arithmetic.
 */
public final class RefundAllocationPolicy {

    public static final String POLICY_VERSION = "original-payment-pro-rata:v1";

    private RefundAllocationPolicy() {
        // Utility class
    }

    /**
     * Calculates the proportional fee and merchant debit components for a partial or full refund.
     *
     * @param originalGrossAmountMinor original payment gross amount in minor units
     * @param originalFeeAmountMinor original payment fee amount in minor units
     * @param alreadyRefundedAmountMinor cumulative gross amount already refunded prior to this operation
     * @param requestedRefundAmountMinor gross amount requested for this refund operation
     * @return RefundAllocation containing the breakdown
     */
    public static RefundAllocation calculateAllocation(
            long originalGrossAmountMinor,
            long originalFeeAmountMinor,
            long alreadyRefundedAmountMinor,
            long requestedRefundAmountMinor
    ) {
        if (originalGrossAmountMinor <= 0) {
            throw new IllegalArgumentException("Original gross amount must be strictly positive: " + originalGrossAmountMinor);
        }
        if (originalFeeAmountMinor < 0) {
            throw new IllegalArgumentException("Original fee amount cannot be negative: " + originalFeeAmountMinor);
        }
        if (alreadyRefundedAmountMinor < 0) {
            throw new IllegalArgumentException("Already refunded amount cannot be negative: " + alreadyRefundedAmountMinor);
        }
        if (requestedRefundAmountMinor <= 0) {
            throw new IllegalArgumentException("Requested refund amount must be strictly positive: " + requestedRefundAmountMinor);
        }
        if (alreadyRefundedAmountMinor + requestedRefundAmountMinor > originalGrossAmountMinor) {
            throw new IllegalArgumentException("Cumulative refund amount exceeds original gross amount");
        }

        long feeBefore = targetFee(originalGrossAmountMinor, originalFeeAmountMinor, alreadyRefundedAmountMinor);
        long feeAfter = targetFee(originalGrossAmountMinor, originalFeeAmountMinor, alreadyRefundedAmountMinor + requestedRefundAmountMinor);

        long thisRefundFee = Math.subtractExact(feeAfter, feeBefore);
        long thisRefundMerchant = Math.subtractExact(requestedRefundAmountMinor, thisRefundFee);

        return new RefundAllocation(requestedRefundAmountMinor, thisRefundMerchant, thisRefundFee, POLICY_VERSION);
    }

    public static long targetFee(long originalGrossAmountMinor, long originalFeeAmountMinor, long cumulativeRefundAmountMinor) {
        if (originalFeeAmountMinor == 0 || cumulativeRefundAmountMinor == 0) {
            return 0L;
        }
        if (cumulativeRefundAmountMinor == originalGrossAmountMinor) {
            return originalFeeAmountMinor;
        }

        // floor(F * R / G) using exact BigInteger checked arithmetic
        return BigInteger.valueOf(originalFeeAmountMinor)
                .multiply(BigInteger.valueOf(cumulativeRefundAmountMinor))
                .divide(BigInteger.valueOf(originalGrossAmountMinor))
                .longValueExact();
    }
}
