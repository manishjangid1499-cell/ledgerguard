package com.ledgerguard.reconciliation.api;

import java.time.Instant;
import java.util.UUID;

public record SnapshotRepairResponse(
        UUID caseId,
        UUID ledgerAccountId,
        String previousBalanceMinor,
        String repairedBalanceMinor,
        String resolutionAction,
        Instant snapshotUpdatedAt
) {
}
