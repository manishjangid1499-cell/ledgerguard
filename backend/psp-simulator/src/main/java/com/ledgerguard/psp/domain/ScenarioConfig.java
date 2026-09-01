package com.ledgerguard.psp.domain;

public record ScenarioConfig(
        SimulatorScenario scenario,
        long delayMs,
        int temporaryFailureCount
) {
    public static final long MAX_DELAY_MS = 30000;
    public static final int MAX_TEMPORARY_FAILURES = 10;

    public ScenarioConfig {
        if (scenario == null) {
            scenario = SimulatorScenario.NORMAL_SUCCESS;
        }
        if (delayMs < 0 || delayMs > MAX_DELAY_MS) {
            throw new IllegalArgumentException("delayMs must be between 0 and " + MAX_DELAY_MS);
        }
        if (temporaryFailureCount < 0 || temporaryFailureCount > MAX_TEMPORARY_FAILURES) {
            throw new IllegalArgumentException("temporaryFailureCount must be between 0 and " + MAX_TEMPORARY_FAILURES);
        }
    }

    public static ScenarioConfig defaultScenario() {
        return new ScenarioConfig(SimulatorScenario.NORMAL_SUCCESS, 0, 0);
    }
}
