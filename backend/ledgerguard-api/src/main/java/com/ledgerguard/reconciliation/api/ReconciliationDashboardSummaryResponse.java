package com.ledgerguard.reconciliation.api;

public record ReconciliationDashboardSummaryResponse(
        long openCases,
        long inReviewCases,
        long resolvedCases,
        long totalCases,
        long totalRuns,
        ReconciliationRunSummaryResponse latestRun
) {
}
