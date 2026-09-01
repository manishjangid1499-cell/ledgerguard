package com.ledgerguard.payment.domain;

import java.util.UUID;

/**
 * Thrown when the target merchant ledger account cannot be found.
 */
public class PaymentDestinationNotFoundException extends RuntimeException {

    private final UUID merchantLedgerAccountId;

    public PaymentDestinationNotFoundException(UUID merchantLedgerAccountId) {
        super("Merchant ledger account not found: " + merchantLedgerAccountId);
        this.merchantLedgerAccountId = merchantLedgerAccountId;
    }

    public UUID getMerchantLedgerAccountId() {
        return merchantLedgerAccountId;
    }
}
