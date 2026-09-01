package com.ledgerguard.refund.application;

import com.ledgerguard.ledger.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to execute an internal payment refund.
 */
public record CreateRefundCommand(
        UUID actorUserId,
        String idempotencyKey,
        UUID paymentId,
        Money refundAmount
) {
    public CreateRefundCommand {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(refundAmount, "refundAmount must not be null");

        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency key must be non-blank and at most 128 characters");
        }
    }
}
