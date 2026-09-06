package com.ledgerguard.lab.api;

import com.ledgerguard.lab.api.dto.EnvironmentStatusView;
import com.ledgerguard.lab.api.dto.LabRunView;
import com.ledgerguard.lab.api.dto.ScenarioMetadataView;
import com.ledgerguard.lab.api.dto.StartScenarioRequest;
import com.ledgerguard.lab.engine.ConcurrencyConflictException;
import com.ledgerguard.lab.engine.LabRunCoordinator;
import com.ledgerguard.lab.model.ScenarioId;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/lab", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173"},
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}
)
public class FailureLabController {

    private final LabRunCoordinator coordinator;
    private final boolean labEnabled;

    public FailureLabController(
            LabRunCoordinator coordinator,
            @Value("${ledgerguard.lab.enabled:false}") boolean labEnabled
    ) {
        this.coordinator = coordinator;
        this.labEnabled = labEnabled;
    }

    @GetMapping("/environment")
    public ResponseEntity<EnvironmentStatusView> getEnvironment() {
        if (!labEnabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new EnvironmentStatusView(
                            "DISABLED", "DOWN", "DOWN", "DOWN",
                            "EPHEMERAL_TESTCONTAINERS",
                            "Failure Lab is disabled. Enable with ledgerguard.lab.enabled=true"
                    ));
        }
        return ResponseEntity.ok(new EnvironmentStatusView(
                "READY", "UP", "UP", "UP",
                "EPHEMERAL_TESTCONTAINERS",
                "Ephemerally isolated. No production database or credentials accessed."
        ));
    }

    @GetMapping("/scenarios")
    public ResponseEntity<List<ScenarioMetadataView>> getScenarios() {
        return ResponseEntity.ok(List.of(
                new ScenarioMetadataView(
                        ScenarioId.OPPOSING_TRANSFERS,
                        "Opposing Concurrent Transfers",
                        "Concurrent transfers between Account A and Account B testing deterministic lock ordering, deadlock freedom, and money conservation.",
                        "CONCURRENCY",
                        List.of("Journal Integrity (Sum Debits == Sum Credits)", "Snapshot Parity (Reconstructed == Snapshot)", "Money Conservation (Delta A + Delta B == 0)", "Available Balance (Available >= 0)"),
                        List.of("Simultaneous opposing transfer execution via CyclicBarrier", "Pessimistic lock acquisition ordering (ORDER BY account_id ASC)")
                ),
                new ScenarioMetadataView(
                        ScenarioId.TIMEOUT_AFTER_COMMIT,
                        "Payout Timeout After Success",
                        "PSP commits payout but drops network connection before response. Ambiguity handling preserves ACTIVE hold and prevents double payment before poller recovery.",
                        "DISTRIBUTED_FAULT",
                        List.of("Journal Integrity (Sum Debits == Sum Credits)", "Single Economic Effect (1 Settlement Journal)", "Available Balance (Available >= 0)"),
                        List.of("HTTP transport connection drop after provider commit", "ProviderStatusPollingService reconciliation sweep")
                ),
                new ScenarioMetadataView(
                        ScenarioId.CORRUPTED_SNAPSHOT,
                        "Snapshot Balance Drift & Auto-Repair",
                        "Deliberate balance drift injected into snapshot. Phase 24 Level 2 reconciliation detects anomaly and Phase 25 auto-repairs snapshot from immutable journals.",
                        "DATA_INTEGRITY",
                        List.of("Journal Integrity (Sum Debits == Sum Credits)", "Snapshot Parity (Restored to Journal Truth)", "Available Balance (Available >= 0)"),
                        List.of("Direct snapshot balance mutation with authorized LabDatabaseTarget", "On-demand Level 2 reconciliation run", "Cryptographically audited auto-repair")
                ),
                new ScenarioMetadataView(
                        ScenarioId.WEBHOOK_RACE,
                        "Concurrent Duplicate Webhooks Race",
                        "5 identical signed webhooks arrive simultaneously. Database event deduplication accepts exactly 1 event, ignores 4 duplicates, and produces a single settlement.",
                        "IDEMPOTENCY",
                        List.of("Journal Integrity (Sum Debits == Sum Credits)", "Single Economic Effect (1 Settlement Journal)", "Available Balance (Available >= 0)"),
                        List.of("HMAC-SHA256 signature verification", "Concurrent HTTP POST ingress via CyclicBarrier(5)", "Unique constraint deduplication on provider_events")
                )
        ));
    }

    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabRunView> startRun(@Valid @RequestBody StartScenarioRequest request) {
        if (!labEnabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        LabRunView view = coordinator.startRun(request.scenarioId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @GetMapping("/runs/active")
    public ResponseEntity<LabRunView> getActiveRun() {
        return coordinator.getActiveRun()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<LabRunView> getRun(@PathVariable("runId") UUID runId) {
        return coordinator.getRun(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/runs")
    public ResponseEntity<List<LabRunView>> getHistory(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        if (limit <= 0) {
            return ResponseEntity.badRequest().build();
        }
        int clampedLimit = Math.min(limit, 100);
        return ResponseEntity.ok(coordinator.getHistory(clampedLimit));
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConcurrencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }
}
