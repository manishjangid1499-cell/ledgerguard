package com.ledgerguard.payment.domain;

/**
 * Thrown when the system PLATFORM_FEES account is missing, misconfigured, or ambiguous.
 */
public class PlatformFeeAccountException extends RuntimeException {

    public PlatformFeeAccountException(String message) {
        super(message);
    }
}
