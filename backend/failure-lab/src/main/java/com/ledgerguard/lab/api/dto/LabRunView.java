package com.ledgerguard.lab.api.dto;

import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;
import com.ledgerguard.lab.model.ScenarioStepEvent;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record LabRunView(
        UUID runId,
        ScenarioId scenarioId,
        ScenarioStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        List<ScenarioStepEvent> timeline,
        List<InvariantCheckResult> invariantResults,
        String error
) {
    public static LabRunView inProgress(UUID runId, ScenarioId scenarioId, ScenarioStatus status, Instant startedAt, List<ScenarioStepEvent> timeline) {
        return new LabRunView(
                runId,
                scenarioId,
                status,
                startedAt,
                null,
                null,
                timeline != null ? List.copyOf(timeline) : Collections.emptyList(),
                Collections.emptyList(),
                null
        );
    }

    public static LabRunView from(ScenarioRunResult result) {
        return new LabRunView(
                result.runId(),
                result.scenarioId(),
                result.status(),
                result.startTime(),
                result.endTime(),
                result.durationMs(),
                result.timeline() != null ? List.copyOf(result.timeline()) : Collections.emptyList(),
                result.invariantResults() != null ? List.copyOf(result.invariantResults()) : Collections.emptyList(),
                result.errorMessage()
        );
    }
}
