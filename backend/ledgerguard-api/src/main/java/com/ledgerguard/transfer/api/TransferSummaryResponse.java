package com.ledgerguard.transfer.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response DTO for a transfer summary item in transfer history list.
 * minor-unit amounts are serialized as decimal strings for JavaScript precision safety.
 */
public record TransferSummaryResponse(
        UUID transferId,
        UUID sourceLedgerAccountId,
        UUID destinationLedgerAccountId,
        String amountMinor,
        String currency,
        UUID journalTransactionId,
        Instant createdAt,
        String direction
) {
}
