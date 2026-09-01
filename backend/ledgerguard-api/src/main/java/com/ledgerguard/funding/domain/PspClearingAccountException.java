package com.ledgerguard.funding.domain;

/**
 * Thrown when the system PSP_CLEARING ledger account cannot be uniquely resolved.
 */
public class PspClearingAccountException extends RuntimeException {

    public PspClearingAccountException(String message) {
        super(message);
    }
}
