package com.ledgerguard.transfer.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public HTTP response payload for completed or replayed internal transfer.
 * minor-unit amounts are serialized as decimal strings for JavaScript precision safety.
 */
public record TransferResponse(
        UUID transferId,
        UUID sourceLedgerAccountId,
        UUID destinationLedgerAccountId,
        String amountMinor,
        String currency,
        UUID journalTransactionId,
        Instant createdAt,
        boolean replayed
) {
    public TransferResponse(
            UUID transferId,
            UUID sourceLedgerAccountId,
            UUID destinationLedgerAccountId,
            long amountMinor,
            String currency,
            UUID journalTransactionId,
            Instant createdAt,
            boolean replayed
    ) {
        this(
                transferId,
                sourceLedgerAccountId,
                destinationLedgerAccountId,
                String.valueOf(amountMinor),
                currency,
                journalTransactionId,
                createdAt,
                replayed
        );
    }
}
