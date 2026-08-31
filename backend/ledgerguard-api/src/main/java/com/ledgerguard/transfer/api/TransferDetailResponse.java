package com.ledgerguard.transfer.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public response DTO for full transfer details including immutable double-entry journal transaction inspector data.
 * All minor-unit values are serialized as decimal strings for JavaScript precision safety.
 */
public record TransferDetailResponse(
        UUID transferId,
        UUID sourceLedgerAccountId,
        UUID destinationLedgerAccountId,
        String amountMinor,
        String currency,
        UUID journalTransactionId,
        Instant createdAt,
        String direction,
        JournalDetailResponse journal
) {

    public record JournalDetailResponse(
            UUID journalTransactionId,
            String status,
            Instant postedAt,
            List<JournalEntryDetailResponse> entries
    ) {
    }

    public record JournalEntryDetailResponse(
            UUID ledgerAccountId,
            String direction,
            String amountMinor
    ) {
    }
}
