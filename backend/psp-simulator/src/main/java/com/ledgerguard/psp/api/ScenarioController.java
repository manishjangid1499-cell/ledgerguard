package com.ledgerguard.psp.api;

import com.ledgerguard.psp.application.InvalidOperationException;
import com.ledgerguard.psp.domain.ScenarioConfig;
import com.ledgerguard.psp.domain.SimulatorScenario;
import com.ledgerguard.psp.infrastructure.ScenarioRegistry;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/simulator/scenarios")
public class ScenarioController {

    private final ScenarioRegistry scenarioRegistry;

    public ScenarioController(ScenarioRegistry scenarioRegistry) {
        this.scenarioRegistry = scenarioRegistry;
    }

    @PutMapping("/{clientOperationId}")
    public ResponseEntity<ScenarioResponse> configureScenario(
            @PathVariable("clientOperationId") UUID clientOperationId,
            @Valid @RequestBody ScenarioRequest request
    ) {
        SimulatorScenario scenario = request.scenario();
        long delayMs = request.delayMs() != null ? request.delayMs() : 0L;
        int failureCount = request.temporaryFailureCount() != null ? request.temporaryFailureCount() : 0;

        try {
            ScenarioConfig config = new ScenarioConfig(scenario, delayMs, failureCount);
            scenarioRegistry.register(clientOperationId, config);

            return ResponseEntity.ok(new ScenarioResponse(
                    clientOperationId,
                    config.scenario(),
                    config.delayMs(),
                    config.temporaryFailureCount()
            ));
        } catch (IllegalArgumentException e) {
            throw new InvalidOperationException(e.getMessage());
        }
    }
}
