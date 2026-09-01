package com.ledgerguard.hold.domain;

/**
 * Lifecycle statuses for a balance hold reservation.
 */
public enum HoldStatus {
    ACTIVE,
    CONSUMED,
    RELEASED,
    EXPIRED
}
