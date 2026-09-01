package com.ledgerguard.psp.api;

import com.ledgerguard.psp.domain.SimulatorScenario;
import jakarta.validation.constraints.NotNull;

public record ScenarioRequest(
        @NotNull(message = "scenario is required")
        SimulatorScenario scenario,

        Long delayMs,

        Integer temporaryFailureCount
) {}
