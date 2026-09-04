package com.ledgerguard.reconciliation.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Representation of a detected reconciliation item.
 * <p>
 * Monetary/numeric values {@code expectedValue} and {@code actualValue} are formatted
 * strictly as exact decimal Strings (via {@code toPlainString()}) to prevent any loss of
 * precision or floating-point IEEE-754 distortion.
 */
public record ReconciliationItemResponse(
        UUID id,
        UUID reconciliationRunId,
        String classification,
        String level,
        String problemType,
        String entityType,
        UUID entityId,
        String observedLocalStatus,
        String expectedValue,
        String actualValue,
        String providerStatus,
        String description,
        Instant detectedAt
) {
}
