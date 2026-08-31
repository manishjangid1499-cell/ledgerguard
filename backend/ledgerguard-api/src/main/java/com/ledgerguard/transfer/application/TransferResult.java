package com.ledgerguard.transfer.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable application result representing a completed or replayed transfer.
 */
public record TransferResult(
        UUID transferId,
        UUID sourceLedgerAccountId,
        UUID destinationLedgerAccountId,
        long amountMinor,
        String currency,
        UUID journalTransactionId,
        Instant createdAt,
        boolean replayed
) {
    public TransferResult {
        Objects.requireNonNull(transferId, "Transfer ID must not be null");
        Objects.requireNonNull(sourceLedgerAccountId, "Source ledger account ID must not be null");
        Objects.requireNonNull(destinationLedgerAccountId, "Destination ledger account ID must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(journalTransactionId, "Journal transaction ID must not be null");
        Objects.requireNonNull(createdAt, "Created at timestamp must not be null");
    }
}
