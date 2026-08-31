package com.ledgerguard.transfer.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public HTTP response payload for completed or replayed internal transfer.
 */
public record TransferResponse(
        UUID transferId,
        UUID sourceLedgerAccountId,
        UUID destinationLedgerAccountId,
        long amountMinor,
        String currency,
        UUID journalTransactionId,
        Instant createdAt,
        boolean replayed
) {
}
