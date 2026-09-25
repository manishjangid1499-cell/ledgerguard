package com.ledgerguard.payment.api;

/**
 * Authoritative summary of all-time received payments, platform fees, refunds,
 * and pending payouts for the authenticated merchant.
 * <p>
 * All monetary amounts are represented as decimal strings of integer minor units (INR paise).
 */
public record MerchantSummaryResponse(
        String grossReceivedMinor,
        String platformFeesMinor,
        String netReceivedMinor,
        String refundedAmountMinor,
        long pendingPayoutsCount,
        String pendingPayoutsAmountMinor,
        String currency
) {}
