package com.ledgerguard.transfer.domain;

/**
 * Exception thrown when a transfer source wallet does not have sufficient funds.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
