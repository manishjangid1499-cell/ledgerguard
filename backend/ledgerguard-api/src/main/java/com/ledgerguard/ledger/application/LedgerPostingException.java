package com.ledgerguard.ledger.application;

/**
 * Thrown when a double-entry journal posting fails application validation or execution.
 */
public class LedgerPostingException extends RuntimeException {

    public LedgerPostingException(String message) {
        super(message);
    }

    public LedgerPostingException(String message, Throwable cause) {
        super(message, cause);
    }
}
