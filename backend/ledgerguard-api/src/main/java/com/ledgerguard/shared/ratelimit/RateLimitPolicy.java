package com.ledgerguard.shared.ratelimit;

/**
 * High-level policy classification categories for API rate limiting.
 */
public enum RateLimitPolicy {
    EXEMPT,
    PUBLIC_AUTH,
    FINANCIAL_WRITE,
    OPS,
    AUTHENTICATED_GENERAL
}
