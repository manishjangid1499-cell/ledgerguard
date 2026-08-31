package com.ledgerguard.ledger.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Result returned upon successful execution of an atomic journal posting.
 */
public record PostingResult(
        UUID journalTransactionId,
        Instant postedAt
) {
    public PostingResult {
        Objects.requireNonNull(journalTransactionId, "Journal transaction ID must not be null");
        Objects.requireNonNull(postedAt, "Posted at timestamp must not be null");
    }
}
