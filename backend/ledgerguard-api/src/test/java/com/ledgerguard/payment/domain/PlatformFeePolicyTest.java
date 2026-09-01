package com.ledgerguard.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformFeePolicyTest {

    @Test
    @DisplayName("Calculates 1% fee with floor rounding for standard and edge amounts")
    void testFeeCalculations() {
        // gross 10000 -> fee 100, net 9900
        FeeCalculation calc1 = PlatformFeePolicy.calculateFee(10000L);
        assertThat(calc1.feeAmountMinor()).isEqualTo(100L);
        assertThat(calc1.merchantNetAmountMinor()).isEqualTo(9900L);
        assertThat(calc1.grossAmountMinor()).isEqualTo(10000L);
        assertThat(calc1.feeBasisPoints()).isEqualTo(100);

        // gross 101 -> fee 1, net 100
        FeeCalculation calc2 = PlatformFeePolicy.calculateFee(101L);
        assertThat(calc2.feeAmountMinor()).isEqualTo(1L);
        assertThat(calc2.merchantNetAmountMinor()).isEqualTo(100L);

        // gross 100 -> fee 1, net 99
        FeeCalculation calc3 = PlatformFeePolicy.calculateFee(100L);
        assertThat(calc3.feeAmountMinor()).isEqualTo(1L);
        assertThat(calc3.merchantNetAmountMinor()).isEqualTo(99L);

        // gross 99 -> fee 0, net 99
        FeeCalculation calc4 = PlatformFeePolicy.calculateFee(99L);
        assertThat(calc4.feeAmountMinor()).isEqualTo(0L);
        assertThat(calc4.merchantNetAmountMinor()).isEqualTo(99L);

        // gross 1 -> fee 0, net 1
        FeeCalculation calc5 = PlatformFeePolicy.calculateFee(1L);
        assertThat(calc5.feeAmountMinor()).isEqualTo(0L);
        assertThat(calc5.merchantNetAmountMinor()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Rejects non-positive gross amounts")
    void testRejectsNonPositiveGross() {
        assertThatThrownBy(() -> PlatformFeePolicy.calculateFee(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlatformFeePolicy.calculateFee(-100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Handles very large long gross amounts without multiplication overflow")
    void testLargeAmountsNoOverflow() {
        long largeGross = 9007199254740995L; // > Number.MAX_SAFE_INTEGER
        FeeCalculation calc = PlatformFeePolicy.calculateFee(largeGross);
        assertThat(calc.grossAmountMinor()).isEqualTo(largeGross);
        assertThat(calc.feeAmountMinor()).isEqualTo(90071992547409L);
        assertThat(calc.merchantNetAmountMinor()).isEqualTo(largeGross - 90071992547409L);
        assertThat(calc.merchantNetAmountMinor() + calc.feeAmountMinor()).isEqualTo(largeGross);

        // Long.MAX_VALUE
        long maxGross = Long.MAX_VALUE;
        FeeCalculation calcMax = PlatformFeePolicy.calculateFee(maxGross);
        assertThat(calcMax.grossAmountMinor()).isEqualTo(maxGross);
        assertThat(calcMax.feeAmountMinor()).isGreaterThan(0L);
        assertThat(calcMax.feeAmountMinor()).isLessThan(maxGross);
        assertThat(calcMax.merchantNetAmountMinor()).isGreaterThan(0L);
        assertThat(calcMax.feeAmountMinor() + calcMax.merchantNetAmountMinor()).isEqualTo(maxGross);
    }
}
