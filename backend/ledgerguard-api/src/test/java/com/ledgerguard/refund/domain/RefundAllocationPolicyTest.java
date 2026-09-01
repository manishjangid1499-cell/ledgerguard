package com.ledgerguard.refund.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundAllocationPolicyTest {

    @Test
    @DisplayName("Calculates proportional allocation for partial refund")
    void testPartialRefundAllocation() {
        // Original: gross 10000, fee 100, net 9900
        // Refund: 2500 -> fee 25, net 2475
        RefundAllocation allocation = RefundAllocationPolicy.calculateAllocation(
                10000L, 100L, 0L, 2500L
        );
        assertThat(allocation.refundAmountMinor()).isEqualTo(2500L);
        assertThat(allocation.merchantDebitAmountMinor()).isEqualTo(2475L);
        assertThat(allocation.feeDebitAmountMinor()).isEqualTo(25L);
        assertThat(allocation.policyVersion()).isEqualTo(RefundAllocationPolicy.POLICY_VERSION);
    }

    @Test
    @DisplayName("Full refund in single operation guarantees exact reversal of original gross, fee, and net")
    void testFullRefundAllocation() {
        // Original: gross 10000, fee 100, net 9900
        RefundAllocation allocation = RefundAllocationPolicy.calculateAllocation(
                10000L, 100L, 0L, 10000L
        );
        assertThat(allocation.refundAmountMinor()).isEqualTo(10000L);
        assertThat(allocation.merchantDebitAmountMinor()).isEqualTo(9900L);
        assertThat(allocation.feeDebitAmountMinor()).isEqualTo(100L);
    }

    @Test
    @DisplayName("Multiple partial refunds telescope to exact original amounts")
    void testMultiplePartialRefundsTelescoping() {
        // Original: gross 10000, fee 100, net 9900
        // Refund 1: 3000 (already 0)
        RefundAllocation r1 = RefundAllocationPolicy.calculateAllocation(10000L, 100L, 0L, 3000L);
        assertThat(r1.refundAmountMinor()).isEqualTo(3000L);
        assertThat(r1.feeDebitAmountMinor()).isEqualTo(30L);
        assertThat(r1.merchantDebitAmountMinor()).isEqualTo(2970L);

        // Refund 2: 3000 (already 3000)
        RefundAllocation r2 = RefundAllocationPolicy.calculateAllocation(10000L, 100L, 3000L, 3000L);
        assertThat(r2.refundAmountMinor()).isEqualTo(3000L);
        assertThat(r2.feeDebitAmountMinor()).isEqualTo(30L);
        assertThat(r2.merchantDebitAmountMinor()).isEqualTo(2970L);

        // Refund 3: 4000 (already 6000)
        RefundAllocation r3 = RefundAllocationPolicy.calculateAllocation(10000L, 100L, 6000L, 4000L);
        assertThat(r3.refundAmountMinor()).isEqualTo(4000L);
        assertThat(r3.feeDebitAmountMinor()).isEqualTo(40L);
        assertThat(r3.merchantDebitAmountMinor()).isEqualTo(3960L);

        // Total
        long totalRefund = r1.refundAmountMinor() + r2.refundAmountMinor() + r3.refundAmountMinor();
        long totalFee = r1.feeDebitAmountMinor() + r2.feeDebitAmountMinor() + r3.feeDebitAmountMinor();
        long totalMerchant = r1.merchantDebitAmountMinor() + r2.merchantDebitAmountMinor() + r3.merchantDebitAmountMinor();

        assertThat(totalRefund).isEqualTo(10000L);
        assertThat(totalFee).isEqualTo(100L);
        assertThat(totalMerchant).isEqualTo(9900L);
    }

    @Test
    @DisplayName("101/1 rounding edge case properly isolates single fee unit on last increment")
    void testRoundingEdgeCase101() {
        // Original: gross 101, fee 1, net 100
        // Refund 1: 50
        RefundAllocation r1 = RefundAllocationPolicy.calculateAllocation(101L, 1L, 0L, 50L);
        assertThat(r1.feeDebitAmountMinor()).isEqualTo(0L);
        assertThat(r1.merchantDebitAmountMinor()).isEqualTo(50L);

        // Refund 2: 50
        RefundAllocation r2 = RefundAllocationPolicy.calculateAllocation(101L, 1L, 50L, 50L);
        assertThat(r2.feeDebitAmountMinor()).isEqualTo(0L);
        assertThat(r2.merchantDebitAmountMinor()).isEqualTo(50L);

        // Refund 3: 1
        RefundAllocation r3 = RefundAllocationPolicy.calculateAllocation(101L, 1L, 100L, 1L);
        assertThat(r3.feeDebitAmountMinor()).isEqualTo(1L);
        assertThat(r3.merchantDebitAmountMinor()).isEqualTo(0L);

        // Verify total exact reversal
        assertThat(r1.refundAmountMinor() + r2.refundAmountMinor() + r3.refundAmountMinor()).isEqualTo(101L);
        assertThat(r1.feeDebitAmountMinor() + r2.feeDebitAmountMinor() + r3.feeDebitAmountMinor()).isEqualTo(1L);
        assertThat(r1.merchantDebitAmountMinor() + r2.merchantDebitAmountMinor() + r3.merchantDebitAmountMinor()).isEqualTo(100L);
    }

    @Test
    @DisplayName("Zero fee original payment produces zero fee refunds")
    void testZeroFeePayment() {
        // Original: gross 99, fee 0, net 99
        RefundAllocation r1 = RefundAllocationPolicy.calculateAllocation(99L, 0L, 0L, 50L);
        assertThat(r1.feeDebitAmountMinor()).isEqualTo(0L);
        assertThat(r1.merchantDebitAmountMinor()).isEqualTo(50L);

        RefundAllocation r2 = RefundAllocationPolicy.calculateAllocation(99L, 0L, 50L, 49L);
        assertThat(r2.feeDebitAmountMinor()).isEqualTo(0L);
        assertThat(r2.merchantDebitAmountMinor()).isEqualTo(49L);
    }

    @Test
    @DisplayName("Rejects invalid and out of bound arguments")
    void testValidationRejections() {
        // Non-positive gross
        assertThatThrownBy(() -> RefundAllocationPolicy.calculateAllocation(0L, 0L, 0L, 100L))
                .isInstanceOf(IllegalArgumentException.class);

        // Negative fee
        assertThatThrownBy(() -> RefundAllocationPolicy.calculateAllocation(100L, -1L, 0L, 100L))
                .isInstanceOf(IllegalArgumentException.class);

        // Negative already refunded
        assertThatThrownBy(() -> RefundAllocationPolicy.calculateAllocation(100L, 1L, -1L, 100L))
                .isInstanceOf(IllegalArgumentException.class);

        // Non-positive requested refund
        assertThatThrownBy(() -> RefundAllocationPolicy.calculateAllocation(100L, 1L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);

        // Over-refund
        assertThatThrownBy(() -> RefundAllocationPolicy.calculateAllocation(100L, 1L, 50L, 51L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Large long amounts do not overflow in intermediate multiplication")
    void testLargeAmountArithmetic() {
        long largeGross = 9007199254740995L;
        long largeFee = 90071992547409L;
        long halfRefund = largeGross / 2;

        RefundAllocation alloc = RefundAllocationPolicy.calculateAllocation(largeGross, largeFee, 0L, halfRefund);
        assertThat(alloc.refundAmountMinor()).isEqualTo(halfRefund);
        assertThat(alloc.feeDebitAmountMinor() + alloc.merchantDebitAmountMinor()).isEqualTo(halfRefund);

        // Test Long.MAX_VALUE
        long maxGross = Long.MAX_VALUE;
        long maxFee = Long.MAX_VALUE / 100;
        RefundAllocation maxAlloc = RefundAllocationPolicy.calculateAllocation(maxGross, maxFee, 0L, maxGross);
        assertThat(maxAlloc.refundAmountMinor()).isEqualTo(maxGross);
        assertThat(maxAlloc.feeDebitAmountMinor()).isEqualTo(maxFee);
        assertThat(maxAlloc.merchantDebitAmountMinor()).isEqualTo(maxGross - maxFee);
    }
}
