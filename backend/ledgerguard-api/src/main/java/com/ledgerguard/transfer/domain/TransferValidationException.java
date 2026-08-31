package com.ledgerguard.transfer.domain;

/**
 * Domain / business validation exception for transfer operations.
 */
public class TransferValidationException extends RuntimeException {

    public TransferValidationException(String message) {
        super(message);
    }

    public TransferValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
