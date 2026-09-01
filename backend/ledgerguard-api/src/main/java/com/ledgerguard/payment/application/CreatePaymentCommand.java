package com.ledgerguard.payment.application;

import com.ledgerguard.ledger.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to execute an internal merchant payment.
 */
public record CreatePaymentCommand(
        UUID actorUserId,
        String idempotencyKey,
        UUID merchantLedgerAccountId,
        Money amount
) {
    public CreatePaymentCommand {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(merchantLedgerAccountId, "merchantLedgerAccountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency key must be non-blank and at most 128 characters");
        }
    }
}
