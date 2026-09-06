package com.ledgerguard.lab.engine;

import com.ledgerguard.lab.model.ScenarioId;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry mapping ScenarioIds to executable ChaosScenario instances.
 * Registered dynamically by test harnesses or runtime scenario providers.
 */
public class ScenarioRegistry {

    private final Map<ScenarioId, ChaosScenario> scenarios = new EnumMap<>(ScenarioId.class);

    public void register(ChaosScenario scenario) {
        if (scenario != null) {
            scenarios.put(scenario.getId(), scenario);
        }
    }

    public Optional<ChaosScenario> get(ScenarioId id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    public Map<ScenarioId, ChaosScenario> getAll() {
        return Collections.unmodifiableMap(scenarios);
    }

    public int size() {
        return scenarios.size();
    }
}
