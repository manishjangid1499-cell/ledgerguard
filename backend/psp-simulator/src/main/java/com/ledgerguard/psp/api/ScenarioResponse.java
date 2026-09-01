package com.ledgerguard.psp.api;

import com.ledgerguard.psp.domain.SimulatorScenario;

import java.util.UUID;

public record ScenarioResponse(
        UUID clientOperationId,
        SimulatorScenario scenario,
        long delayMs,
        int temporaryFailureCount
) {}
