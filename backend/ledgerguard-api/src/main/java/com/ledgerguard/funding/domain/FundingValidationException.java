package com.ledgerguard.funding.domain;

/**
 * Thrown when a funding request fails business or parameter validation.
 */
public class FundingValidationException extends RuntimeException {

    public FundingValidationException(String message) {
        super(message);
    }
}
