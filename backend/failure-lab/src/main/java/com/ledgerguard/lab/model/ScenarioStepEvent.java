package com.ledgerguard.lab.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Structured timeline step event emitted during chaos scenario execution.
 */
public record ScenarioStepEvent(
        Instant timestamp,
        String stepName,
        String description,
        String details
) {
    public ScenarioStepEvent {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(stepName, "stepName must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }

    public static ScenarioStepEvent of(String stepName, String description) {
        return new ScenarioStepEvent(Instant.now(), stepName, description, null);
    }

    public static ScenarioStepEvent of(String stepName, String description, String details) {
        return new ScenarioStepEvent(Instant.now(), stepName, description, details);
    }
}
