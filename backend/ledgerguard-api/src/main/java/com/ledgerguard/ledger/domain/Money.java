package com.ledgerguard.ledger.domain;

import java.io.Serializable;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable value object representing a monetary amount in integer minor units (e.g. paise for INR).
 * Floating-point arithmetic is strictly avoided. All operations check for arithmetic overflow and currency match.
 */
public final class Money implements Comparable<Money>, Serializable {

    public static final Currency INR = Currency.getInstance("INR");

    private final Currency currency;
    private final long minorUnits;

    private Money(long minorUnits, Currency currency) {
        this.currency = Objects.requireNonNull(currency, "Currency must not be null");
        this.minorUnits = minorUnits;
    }

    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money ofMinor(long minorUnits, String currencyCode) {
        Objects.requireNonNull(currencyCode, "Currency code must not be null");
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public static Money inr(long minorUnits) {
        return new Money(minorUnits, INR);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public static Money zeroInr() {
        return new Money(0L, INR);
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getCurrencyCode() {
        return currency.getCurrencyCode();
    }

    public long getMinorUnits() {
        return minorUnits;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public Money plus(Money other) {
        validateSameCurrency(other);
        long result = Math.addExact(this.minorUnits, other.minorUnits);
        return new Money(result, this.currency);
    }

    public Money minus(Money other) {
        validateSameCurrency(other);
        long result = Math.subtractExact(this.minorUnits, other.minorUnits);
        return new Money(result, this.currency);
    }

    public Money negated() {
        long result = Math.negateExact(this.minorUnits);
        return new Money(result, this.currency);
    }

    private void validateSameCurrency(Money other) {
        Objects.requireNonNull(other, "Money operand must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: cannot operate between " + this.currency.getCurrencyCode()
                            + " and " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public int compareTo(Money other) {
        validateSameCurrency(other);
        return Long.compare(this.minorUnits, other.minorUnits);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return minorUnits == money.minorUnits && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, minorUnits);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + minorUnits + " (minor units)";
    }
}
