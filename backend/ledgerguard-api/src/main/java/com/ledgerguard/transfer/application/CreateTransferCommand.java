package com.ledgerguard.transfer.application;

import com.ledgerguard.ledger.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable command to initiate an internal wallet transfer.
 * Note: The source account is intentionally NOT specified here; it is resolved server-side from actorUserId.
 */
public record CreateTransferCommand(
        UUID actorUserId,
        UUID destinationLedgerAccountId,
        Money amount,
        String idempotencyKey
) {
    public CreateTransferCommand {
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");
        Objects.requireNonNull(destinationLedgerAccountId, "Destination ledger account ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
    }

    public static CreateTransferCommand of(UUID actorUserId,
                                           UUID destinationLedgerAccountId,
                                           Money amount,
                                           String idempotencyKey) {
        return new CreateTransferCommand(actorUserId, destinationLedgerAccountId, amount, idempotencyKey);
    }
}
