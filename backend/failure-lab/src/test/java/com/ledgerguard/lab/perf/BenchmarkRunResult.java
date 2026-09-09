package com.ledgerguard.lab.perf;

import java.util.Map;

/**
 * Record representing the outcome and metrics of one measured benchmark repetition.
 */
public record BenchmarkRunResult(
        int poolSize,
        String workload,
        int concurrency,
        int repetition,
        int operationsRequested,
        int successCount,
        int failureCount,
        double elapsedMs,
        double throughputOpsPerSecond,
        double latencyMinMs,
        double latencyAverageMs,
        double latencyP50Ms,
        double latencyP95Ms,
        double latencyP99Ms,
        double latencyMaxMs,
        int maxActiveConnections,
        int minIdleConnections,
        int maxPendingThreads,
        int samplesWithPendingThreads,
        boolean connectionAcquisitionMetricAvailable,
        long connectionAcquisitionCountDelta,
        double connectionAcquisitionTotalMsDelta,
        int maxDatabaseLockWaiters,
        int samplesWithDatabaseLockWaiters,
        long deadlocksBefore,
        long deadlocksAfter,
        long deadlocksDelta,
        boolean financialInvariantsPassed,
        Map<String, Integer> errorClassifications
) {
}
