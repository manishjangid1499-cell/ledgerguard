package com.ledgerguard.psp.infrastructure;

import com.ledgerguard.psp.domain.ScenarioConfig;
import com.ledgerguard.psp.domain.SimulatorScenario;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ScenarioRegistry {

    public static class ScenarioState {
        private final ScenarioConfig config;
        private final AtomicInteger remainingTemporaryFailures;

        public ScenarioState(ScenarioConfig config) {
            this.config = config;
            this.remainingTemporaryFailures = new AtomicInteger(config.temporaryFailureCount());
        }

        public ScenarioConfig getConfig() {
            return config;
        }

        public boolean consumeTemporaryFailure() {
            int current;
            do {
                current = remainingTemporaryFailures.get();
                if (current <= 0) {
                    return false;
                }
            } while (!remainingTemporaryFailures.compareAndSet(current, current - 1));
            return true;
        }

        public boolean hasRemainingTemporaryFailures() {
            return remainingTemporaryFailures.get() > 0;
        }
    }

    private final ConcurrentHashMap<UUID, ScenarioState> registry = new ConcurrentHashMap<>();

    public void register(UUID clientOperationId, ScenarioConfig config) {
        if (clientOperationId == null) {
            throw new IllegalArgumentException("clientOperationId cannot be null");
        }
        if (config == null) {
            config = ScenarioConfig.defaultScenario();
        }
        registry.put(clientOperationId, new ScenarioState(config));
    }

    public ScenarioState getState(UUID clientOperationId) {
        if (clientOperationId == null) {
            return new ScenarioState(ScenarioConfig.defaultScenario());
        }
        return registry.getOrDefault(clientOperationId, new ScenarioState(ScenarioConfig.defaultScenario()));
    }

    public void clear(UUID clientOperationId) {
        if (clientOperationId != null) {
            registry.remove(clientOperationId);
        }
    }

    public void clearAll() {
        registry.clear();
    }
}
