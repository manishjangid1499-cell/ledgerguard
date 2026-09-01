package com.ledgerguard.payout.application;

import com.ledgerguard.ledger.domain.Money;

import java.util.Objects;
import java.util.UUID;

public record CreatePayoutCommand(
        UUID actorUserId,
        String idempotencyKey,
        Money amount
) {
    public CreatePayoutCommand {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
