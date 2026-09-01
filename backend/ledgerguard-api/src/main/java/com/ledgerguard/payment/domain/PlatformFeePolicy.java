package com.ledgerguard.payment.domain;

import java.math.BigInteger;

/**
 * Deterministic platform fee calculation policy.
 * Standard rule: 100 basis points (1%), rounded DOWN to the nearest minor unit (floor rounding).
 * Zero floating-point arithmetic.
 */
public final class PlatformFeePolicy {

    public static final int FEE_BASIS_POINTS = 100;
    public static final int BPS_DENOMINATOR = 10000;

    private static final BigInteger BIG_BPS = BigInteger.valueOf(FEE_BASIS_POINTS);
    private static final BigInteger BIG_DENOMINATOR = BigInteger.valueOf(BPS_DENOMINATOR);

    private PlatformFeePolicy() {
        // Utility class
    }

    /**
     * Calculates platform fee and merchant net amount for a given gross minor units amount.
     *
     * @param grossAmountMinor gross payment amount in minor units
     * @return FeeCalculation result
     */
    public static FeeCalculation calculateFee(long grossAmountMinor) {
        if (grossAmountMinor <= 0) {
            throw new IllegalArgumentException("Gross amount must be strictly positive: " + grossAmountMinor);
        }

        // fee = floor(gross * 100 / 10000) using exact BigInteger checked arithmetic to prevent long overflow
        long feeAmountMinor = BigInteger.valueOf(grossAmountMinor)
                .multiply(BIG_BPS)
                .divide(BIG_DENOMINATOR)
                .longValueExact();

        long merchantNetAmountMinor = Math.subtractExact(grossAmountMinor, feeAmountMinor);

        return new FeeCalculation(grossAmountMinor, feeAmountMinor, merchantNetAmountMinor, FEE_BASIS_POINTS);
    }
}
