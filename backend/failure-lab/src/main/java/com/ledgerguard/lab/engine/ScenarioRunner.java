package com.ledgerguard.lab.engine;

import com.ledgerguard.lab.guard.EnvironmentGuard;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;
import com.ledgerguard.lab.model.ScenarioStepEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Authoritative runner engine for executing failure lab chaos scenarios.
 * Enforces environmental safety, concurrency bounds, execution timeouts, and result recording.
 */
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);
    private static final int MAX_HISTORY_SIZE = 100;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final ConcurrencyGuard concurrencyGuard;
    private final Map<UUID, ScenarioRunResult> runHistory = new ConcurrentHashMap<>();
    private final Deque<UUID> historyOrder = new ArrayDeque<>();

    public ScenarioRunner() {
        this(new ConcurrencyGuard());
    }

    public ScenarioRunner(ConcurrencyGuard concurrencyGuard) {
        this.concurrencyGuard = Objects.requireNonNull(concurrencyGuard, "concurrencyGuard must not be null");
    }

    public ScenarioRunResult runScenario(ChaosScenario scenario, DataSource dataSource) {
        return runScenario(scenario, dataSource, ScenarioEventSink.NO_OP, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public ScenarioRunResult runScenario(ChaosScenario scenario, DataSource dataSource, long timeout, TimeUnit unit) {
        return runScenario(scenario, dataSource, ScenarioEventSink.NO_OP, timeout, unit);
    }

    public ScenarioRunResult runScenario(ChaosScenario scenario, DataSource dataSource, ScenarioEventSink sink, long timeout, TimeUnit unit) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        ScenarioEventSink eventSink = sink != null ? sink : ScenarioEventSink.NO_OP;

        UUID runId = UUID.randomUUID();
        Instant startTime = Instant.now();
        List<ScenarioStepEvent> timeline = new ArrayList<>();

        emit(timeline, eventSink, ScenarioStepEvent.of("INIT", "Starting scenario: " + scenario.getId().getKey()));

        // 1. Hard safety check against target database
        try {
            EnvironmentGuard.assertLabEnvironment(dataSource);
            emit(timeline, eventSink, ScenarioStepEvent.of("ENVIRONMENT_VERIFIED", "Target database verified as safe lab/test environment"));
        } catch (SecurityException e) {
            log.error("Environment verification failed for run {}: {}", runId, e.getMessage());
            Instant endTime = Instant.now();
            ScenarioRunResult failureResult = new ScenarioRunResult(
                    runId, scenario.getId(), ScenarioStatus.FAILED,
                    startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                    Collections.emptyList(), timeline, "Security abort: " + e.getMessage()
            );
            recordResult(failureResult);
            return failureResult;
        }

        // 2. Concurrency guard acquisition
        boolean acquired;
        try {
            acquired = concurrencyGuard.tryAcquire(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Instant endTime = Instant.now();
            ScenarioRunResult interruptedResult = new ScenarioRunResult(
                    runId, scenario.getId(), ScenarioStatus.FAILED,
                    startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                    Collections.emptyList(), timeline, "Interrupted while waiting for concurrency lock"
            );
            recordResult(interruptedResult);
            return interruptedResult;
        }

        if (!acquired) {
            Instant endTime = Instant.now();
            ScenarioRunResult lockedResult = new ScenarioRunResult(
                    runId, scenario.getId(), ScenarioStatus.FAILED,
                    startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                    Collections.emptyList(), timeline, "Failed to acquire exclusive scenario lock (another run is in progress)"
            );
            recordResult(lockedResult);
            return lockedResult;
        }

        try {
            emit(timeline, eventSink, ScenarioStepEvent.of("LOCK_ACQUIRED", "Acquired exclusive scenario runner lock"));

            // 3. Timed asynchronous execution
            CompletableFuture<ScenarioRunResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return scenario.execute(runId, dataSource, eventSink);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            ScenarioRunResult result = future.get(timeout, unit);
            recordResult(result);
            return result;

        } catch (TimeoutException e) {
            log.warn("Scenario {} timed out after {} {}", scenario.getId(), timeout, unit);
            Instant endTime = Instant.now();
            emit(timeline, eventSink, ScenarioStepEvent.of("TIMEOUT", "Scenario exceeded maximum duration: " + timeout + " " + unit));
            ScenarioRunResult timeoutResult = new ScenarioRunResult(
                    runId, scenario.getId(), ScenarioStatus.TIMED_OUT,
                    startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                    Collections.emptyList(), timeline, "Scenario timed out after " + timeout + " " + unit
            );
            recordResult(timeoutResult);
            return timeoutResult;

        } catch (ExecutionException e) {
            log.error("Execution error in scenario {}: {}", scenario.getId(), e.getCause().getMessage(), e);
            Instant endTime = Instant.now();
            emit(timeline, eventSink, ScenarioStepEvent.of("ERROR", "Execution threw exception: " + e.getCause().getMessage()));
            ScenarioRunResult errorResult = new ScenarioRunResult(
                    runId, scenario.getId(), ScenarioStatus.FAILED,
                    startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                    Collections.emptyList(), timeline, e.getCause().getMessage()
            );
            recordResult(errorResult);
            return errorResult;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Instant endTime = Instant.now();
            ScenarioRunResult interruptedResult = new ScenarioRunResult(
                    runId, scenario.getId(), ScenarioStatus.FAILED,
                    startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                    Collections.emptyList(), timeline, "Scenario execution interrupted"
            );
            recordResult(interruptedResult);
            return interruptedResult;

        } finally {
            concurrencyGuard.release();
        }
    }

    private void emit(List<ScenarioStepEvent> timeline, ScenarioEventSink sink, ScenarioStepEvent event) {
        timeline.add(event);
        if (sink != null) {
            sink.onStep(event);
        }
    }

    private synchronized void recordResult(ScenarioRunResult result) {
        if (historyOrder.size() >= MAX_HISTORY_SIZE) {
            UUID evicted = historyOrder.pollFirst();
            if (evicted != null) {
                runHistory.remove(evicted);
            }
        }
        historyOrder.addLast(result.runId());
        runHistory.put(result.runId(), result);
    }

    public Optional<ScenarioRunResult> getRunResult(UUID runId) {
        return Optional.ofNullable(runHistory.get(runId));
    }

    public List<ScenarioRunResult> getHistory() {
        List<ScenarioRunResult> list = new ArrayList<>();
        synchronized (this) {
            for (UUID id : historyOrder) {
                ScenarioRunResult r = runHistory.get(id);
                if (r != null) {
                    list.add(r);
                }
            }
        }
        return Collections.unmodifiableList(list);
    }
}
