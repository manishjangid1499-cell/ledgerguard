package com.ledgerguard.outbox.domain;

import java.util.Objects;

/**
 * Immutable payload record for TRANSFER_COMPLETED domain events.
 * Monetary amounts are serialized as decimal strings for precision safety.
 */
public record TransferCompletedPayload(
        String transferId,
        String sourceLedgerAccountId,
        String destinationLedgerAccountId,
        String amountMinor,
        String currency,
        String journalTransactionId
) {
    public TransferCompletedPayload {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(sourceLedgerAccountId, "sourceLedgerAccountId must not be null");
        Objects.requireNonNull(destinationLedgerAccountId, "destinationLedgerAccountId must not be null");
        Objects.requireNonNull(amountMinor, "amountMinor must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(journalTransactionId, "journalTransactionId must not be null");
    }
}
