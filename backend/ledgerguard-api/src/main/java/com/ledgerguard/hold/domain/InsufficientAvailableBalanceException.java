package com.ledgerguard.hold.domain;

/**
 * Thrown when an internal balance hold creation request exceeds current available wallet balance.
 */
public class InsufficientAvailableBalanceException extends RuntimeException {

    public InsufficientAvailableBalanceException(String message) {
        super(message);
    }
}
