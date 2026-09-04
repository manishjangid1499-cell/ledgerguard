package com.ledgerguard.reconciliation.api;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunSummaryResponse(
        UUID id,
        String status,
        String triggerSource,
        Instant startedAt,
        Instant completedAt,
        long journalsChecked,
        long accountsChecked,
        long operationsChecked,
        long discrepancyCount,
        long unresolvedCount,
        String failureReason
) {
}
