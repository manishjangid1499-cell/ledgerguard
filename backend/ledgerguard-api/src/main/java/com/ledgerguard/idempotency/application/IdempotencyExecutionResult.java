package com.ledgerguard.idempotency.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of executing an idempotent operation.
 *
 * @param resultId the committed operation/resource result identifier
 * @param replayed true if a prior successful invocation already completed and returned its stored result ID;
 *                 false if this invocation performed the execution
 */
public record IdempotencyExecutionResult(
        UUID resultId,
        boolean replayed
) {

    public IdempotencyExecutionResult {
        Objects.requireNonNull(resultId, "Result ID must not be null");
    }

    public static IdempotencyExecutionResult executed(UUID resultId) {
        return new IdempotencyExecutionResult(resultId, false);
    }

    public static IdempotencyExecutionResult replayed(UUID resultId) {
        return new IdempotencyExecutionResult(resultId, true);
    }
}
