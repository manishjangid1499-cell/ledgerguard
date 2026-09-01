package com.ledgerguard.funding.infrastructure;

/**
 * Thrown when an HTTP transport, I/O, connect, or read timeout occurs during external PSP communication.
 */
public class PspTransportException extends RuntimeException {

    public PspTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
