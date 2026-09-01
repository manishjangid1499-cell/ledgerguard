package com.ledgerguard.hold.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AvailableBalanceTest {

    @Test
    @DisplayName("Underflow safety: posted Long.MIN_VALUE minus 1 does not wrap to positive long")
    void underflowSafetyLongMinValueMinusOne() {
        AvailableBalance balance = AvailableBalance.of(Long.MIN_VALUE, 1L);

        // Mathematical value: -9223372036854775808 - 1 = -9223372036854775809
        assertThat(balance.availableBalanceMinorString()).isEqualTo("-9223372036854775809");
        assertThat(balance.availableBalance()).isEqualTo(
                BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)
        );
        // Spending authorization check MUST be false
        assertThat(balance.hasAvailable(1L)).isFalse();
        assertThat(balance.hasAvailable(1000L)).isFalse();
    }

    @Test
    @DisplayName("Zero posted balance minus Long.MAX_VALUE held equals -Long.MAX_VALUE")
    void zeroPostedMinusLongMaxValue() {
        AvailableBalance balance = AvailableBalance.of(0L, Long.MAX_VALUE);

        assertThat(balance.availableBalanceMinorString()).isEqualTo("-" + Long.MAX_VALUE);
        assertThat(balance.hasAvailable(1L)).isFalse();
    }

    @Test
    @DisplayName("Long.MAX_VALUE posted minus Long.MAX_VALUE held equals 0")
    void longMaxValuePostedMinusLongMaxValueHeld() {
        AvailableBalance balance = AvailableBalance.of(Long.MAX_VALUE, Long.MAX_VALUE);

        assertThat(balance.availableBalanceMinorString()).isEqualTo("0");
        assertThat(balance.hasAvailable(0L)).isFalse(); // non-positive requests return false
        assertThat(balance.hasAvailable(1L)).isFalse();
    }

    @Test
    @DisplayName("Long.MAX_VALUE posted minus 0 held equals Long.MAX_VALUE")
    void longMaxValuePostedMinusZeroHeld() {
        AvailableBalance balance = AvailableBalance.of(Long.MAX_VALUE, 0L);

        assertThat(balance.availableBalanceMinorString()).isEqualTo(String.valueOf(Long.MAX_VALUE));
        assertThat(balance.hasAvailable(Long.MAX_VALUE)).isTrue();
        assertThat(balance.hasAvailable(1000L)).isTrue();
    }

    @Test
    @DisplayName("Negative posted balance with active holds produces exact negative available balance")
    void negativePostedBalanceWithActiveHolds() {
        AvailableBalance balance = AvailableBalance.of(-5000L, 3000L);

        assertThat(balance.availableBalanceMinorString()).isEqualTo("-8000");
        assertThat(balance.hasAvailable(1L)).isFalse();
    }
}
