package com.ledgerguard.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    @DisplayName("Money represents exact integer minor units without floating-point inaccuracies")
    void minorUnitRepresentationIsExact() {
        Money money = Money.inr(12345L);

        assertThat(money.getMinorUnits()).isEqualTo(12345L);
        assertThat(money.getCurrency()).isEqualTo(INR);
        assertThat(money.getCurrencyCode()).isEqualTo("INR");
        assertThat(money.isPositive()).isTrue();
        assertThat(money.isNegative()).isFalse();
        assertThat(money.isZero()).isFalse();
    }

    @Test
    @DisplayName("Zero money representation works as expected")
    void zeroMoneyBehaviors() {
        Money zeroInr = Money.zeroInr();

        assertThat(zeroInr.getMinorUnits()).isEqualTo(0L);
        assertThat(zeroInr.isZero()).isTrue();
        assertThat(zeroInr.isPositive()).isFalse();
        assertThat(zeroInr.isNegative()).isFalse();
    }

    @Test
    @DisplayName("Addition works with exact minor units for same currency")
    void plusSucceedsForSameCurrency() {
        Money a = Money.inr(5000L);
        Money b = Money.inr(2500L);

        Money result = a.plus(b);

        assertThat(result.getMinorUnits()).isEqualTo(7500L);
        assertThat(result.getCurrency()).isEqualTo(INR);
    }

    @Test
    @DisplayName("Subtraction works with exact minor units for same currency")
    void minusSucceedsForSameCurrency() {
        Money a = Money.inr(5000L);
        Money b = Money.inr(2000L);

        Money result = a.minus(b);

        assertThat(result.getMinorUnits()).isEqualTo(3000L);
        assertThat(result.getCurrency()).isEqualTo(INR);
    }

    @Test
    @DisplayName("Subtraction can produce negative money representing signed balances")
    void minusCanProduceNegativeMoney() {
        Money a = Money.inr(2000L);
        Money b = Money.inr(5000L);

        Money result = a.minus(b);

        assertThat(result.getMinorUnits()).isEqualTo(-3000L);
        assertThat(result.isNegative()).isTrue();
        assertThat(result.isPositive()).isFalse();
    }

    @Test
    @DisplayName("Equality and compareTo work on currency and minor units")
    void equalityAndComparison() {
        Money a = Money.inr(1000L);
        Money b = Money.inr(1000L);
        Money c = Money.inr(2000L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
        assertThat(a.compareTo(c)).isLessThan(0);
        assertThat(c.compareTo(a)).isGreaterThan(0);
        assertThat(a.compareTo(b)).isZero();
    }

    @Test
    @DisplayName("Arithmetic involving different currencies must fail explicitly")
    void differentCurrencyArithmeticThrowsException() {
        Money inrMoney = Money.inr(1000L);
        Money usdMoney = Money.ofMinor(1000L, USD);

        assertThatThrownBy(() -> inrMoney.plus(usdMoney))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");

        assertThatThrownBy(() -> inrMoney.minus(usdMoney))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");

        assertThatThrownBy(() -> inrMoney.compareTo(usdMoney))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    @DisplayName("Addition overflow throws checked ArithmeticException")
    void additionOverflowThrowsArithmeticException() {
        Money max = Money.inr(Long.MAX_VALUE);
        Money one = Money.inr(1L);

        assertThatThrownBy(() -> max.plus(one))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("Subtraction overflow throws checked ArithmeticException")
    void subtractionOverflowThrowsArithmeticException() {
        Money min = Money.inr(Long.MIN_VALUE);
        Money one = Money.inr(1L);

        assertThatThrownBy(() -> min.minus(one))
                .isInstanceOf(ArithmeticException.class);
    }
}
