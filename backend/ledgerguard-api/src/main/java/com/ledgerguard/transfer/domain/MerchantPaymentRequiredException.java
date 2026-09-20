package com.ledgerguard.transfer.domain;

/**
 * Domain exception thrown when a wallet transfer is attempted to a Merchant wallet,
 * requiring the commercial Payment flow instead.
 */
public class MerchantPaymentRequiredException extends RuntimeException {

    public MerchantPaymentRequiredException(String message) {
        super(message);
    }

    public MerchantPaymentRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
