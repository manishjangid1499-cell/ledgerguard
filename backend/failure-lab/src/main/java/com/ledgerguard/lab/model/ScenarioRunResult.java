package com.ledgerguard.lab.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable report of a completed failure lab chaos scenario execution.
 */
public record ScenarioRunResult(
        UUID runId,
        ScenarioId scenarioId,
        ScenarioStatus status,
        Instant startTime,
        Instant endTime,
        long durationMs,
        List<InvariantCheckResult> invariantResults,
        List<ScenarioStepEvent> timeline,
        String errorMessage
) {
    public ScenarioRunResult {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(endTime, "endTime must not be null");
        invariantResults = invariantResults != null ? List.copyOf(invariantResults) : Collections.emptyList();
        timeline = timeline != null ? List.copyOf(timeline) : Collections.emptyList();
    }

    public boolean isAllInvariantsPassed() {
        return invariantResults.stream().allMatch(InvariantCheckResult::passed);
    }
}
