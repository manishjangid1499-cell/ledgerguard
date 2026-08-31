package com.ledgerguard.idempotency.application;

import com.ledgerguard.idempotency.domain.RequestFingerprint;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable command specifying the execution context for an idempotent operation.
 *
 * @param actorUserId root actor user ID
 * @param operation logical operation namespace (max 64 chars)
 * @param idempotencyKey caller-supplied idempotency key (max 128 chars)
 * @param requestFingerprint deterministic 64-character lowercase hexadecimal SHA-256 request fingerprint
 */
public record IdempotencyCommand(
        UUID actorUserId,
        String operation,
        String idempotencyKey,
        String requestFingerprint
) {

    public IdempotencyCommand {
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");
        Objects.requireNonNull(operation, "Operation must not be null");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must not be null");
        Objects.requireNonNull(requestFingerprint, "Request fingerprint must not be null");

        if (operation.isBlank()) {
            throw new IllegalArgumentException("Operation must not be blank");
        }
        if (operation.length() > 64) {
            throw new IllegalArgumentException("Operation length must not exceed 64 characters (was " + operation.length() + ")");
        }

        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency key length must not exceed 128 characters (was " + idempotencyKey.length() + ")");
        }

        // Validate 64 lowercase hex format
        RequestFingerprint.fromHex(requestFingerprint);
    }

    public static IdempotencyCommand of(UUID actorUserId, String operation, String idempotencyKey, String requestFingerprint) {
        return new IdempotencyCommand(actorUserId, operation, idempotencyKey, requestFingerprint);
    }

    public static IdempotencyCommand of(UUID actorUserId, String operation, String idempotencyKey, RequestFingerprint requestFingerprint) {
        Objects.requireNonNull(requestFingerprint, "Request fingerprint must not be null");
        return new IdempotencyCommand(actorUserId, operation, idempotencyKey, requestFingerprint.getValue());
    }
}
