package com.ledgerguard.hold.domain;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Value object representing the decomposition of a wallet's financial state:
 * posted balance (authoritative snapshot), active hold amount, and derived available balance.
 *
 * All balance derivations use BigInteger to guarantee mathematical exactness and prevent
 * signed integer overflow/wrap-around.
 */
public record AvailableBalance(
        BigInteger postedBalance,
        BigInteger activeHoldAmount,
        BigInteger availableBalance
) {
    public AvailableBalance {
        Objects.requireNonNull(postedBalance, "Posted balance must not be null");
        Objects.requireNonNull(activeHoldAmount, "Active hold amount must not be null");
        Objects.requireNonNull(availableBalance, "Available balance must not be null");
    }

    public static AvailableBalance of(long postedBalanceMinor, long activeHoldAmountMinor) {
        BigInteger posted = BigInteger.valueOf(postedBalanceMinor);
        BigInteger held = BigInteger.valueOf(activeHoldAmountMinor);
        BigInteger available = posted.subtract(held);
        return new AvailableBalance(posted, held, available);
    }

    public static AvailableBalance of(BigInteger postedBalance, BigInteger activeHoldAmount) {
        Objects.requireNonNull(postedBalance, "Posted balance must not be null");
        Objects.requireNonNull(activeHoldAmount, "Active hold amount must not be null");
        return new AvailableBalance(postedBalance, activeHoldAmount, postedBalance.subtract(activeHoldAmount));
    }

    public boolean hasAvailable(long requestedAmountMinor) {
        if (requestedAmountMinor <= 0) {
            return false;
        }
        return this.availableBalance.compareTo(BigInteger.valueOf(requestedAmountMinor)) >= 0;
    }

    public BigInteger getPostedBalance() {
        return postedBalance;
    }

    public BigInteger getActiveHoldAmount() {
        return activeHoldAmount;
    }

    public BigInteger getAvailableBalance() {
        return availableBalance;
    }

    public long postedBalanceMinor() {
        return postedBalance.longValueExact();
    }

    public long activeHoldAmountMinor() {
        return activeHoldAmount.longValueExact();
    }

    public long availableBalanceMinor() {
        return availableBalance.longValueExact();
    }

    public String availableBalanceMinorString() {
        return availableBalance.toString();
    }
}
