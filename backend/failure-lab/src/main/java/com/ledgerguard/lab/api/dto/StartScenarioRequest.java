package com.ledgerguard.lab.api.dto;

import com.ledgerguard.lab.model.ScenarioId;
import jakarta.validation.constraints.NotNull;

public record StartScenarioRequest(
        @NotNull(message = "scenarioId must not be null")
        ScenarioId scenarioId
) {}
