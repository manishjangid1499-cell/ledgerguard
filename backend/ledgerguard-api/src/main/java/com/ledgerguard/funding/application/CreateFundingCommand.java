package com.ledgerguard.funding.application;

import com.ledgerguard.ledger.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Command representing an external customer wallet funding request.
 */
public record CreateFundingCommand(
        UUID actorUserId,
        String idempotencyKey,
        Money amount
) {
    public CreateFundingCommand {
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
    }
}
