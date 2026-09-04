package com.ledgerguard.reconciliation.api;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationCaseResponse(
        UUID id,
        UUID reconciliationItemId,
        String status,
        UUID assignedToUserId,
        UUID resolvedByUserId,
        String resolutionAction,
        String resolutionNote,
        Instant openedAt,
        Instant updatedAt,
        Instant resolvedAt,
        ReconciliationItemResponse item
) {
}
