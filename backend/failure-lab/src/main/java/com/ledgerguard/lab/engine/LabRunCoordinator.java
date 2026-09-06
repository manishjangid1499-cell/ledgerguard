package com.ledgerguard.lab.engine;

import com.ledgerguard.lab.api.dto.LabRunView;
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
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous coordinator for executing failure lab chaos scenarios.
 * Enforces single-run concurrency guard, timeouts, in-flight event streaming,
 * worker thread safety (preventing overlap on timeout), and bounded history ring buffer.
 */
public class LabRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LabRunCoordinator.class);
    private static final int MAX_HISTORY_SIZE = 100;
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final ScenarioRegistry scenarioRegistry;
    private final DataSource dataSource;
    private final ConcurrencyGuard concurrencyGuard;
    private final ExecutorService executor;
    private final long timeoutMs;

    private final AtomicReference<ActiveRunState> activeRun = new AtomicReference<>(null);
    private final AtomicReference<Thread> activeWorkerThread = new AtomicReference<>(null);
    private final Map<UUID, ScenarioRunResult> runHistory = new ConcurrentHashMap<>();
    private final Deque<UUID> historyOrder = new ArrayDeque<>();

    public LabRunCoordinator(ScenarioRegistry scenarioRegistry, DataSource dataSource) {
        this(scenarioRegistry, dataSource, new ConcurrencyGuard(), new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "lab-run-coordinator");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        ), DEFAULT_TIMEOUT_MS);
    }

    public LabRunCoordinator(
            ScenarioRegistry scenarioRegistry,
            DataSource dataSource,
            ConcurrencyGuard concurrencyGuard,
            ExecutorService executor
    ) {
        this(scenarioRegistry, dataSource, concurrencyGuard, executor, DEFAULT_TIMEOUT_MS);
    }

    public LabRunCoordinator(
            ScenarioRegistry scenarioRegistry,
            DataSource dataSource,
            ConcurrencyGuard concurrencyGuard,
            ExecutorService executor,
            long timeoutMs
    ) {
        this.scenarioRegistry = Objects.requireNonNull(scenarioRegistry, "scenarioRegistry must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.concurrencyGuard = Objects.requireNonNull(concurrencyGuard, "concurrencyGuard must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    public synchronized LabRunView startRun(ScenarioId scenarioId) {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");

        ChaosScenario scenario = scenarioRegistry.get(scenarioId)
                .orElseThrow(() -> new NoSuchElementException("Unknown or unregistered scenario: " + scenarioId));

        // 1. Concurrency check: must not be locked, no active run, and previous worker thread must not still be executing
        Thread currentWorker = activeWorkerThread.get();
        if (concurrencyGuard.isLocked() || activeRun.get() != null || (currentWorker != null && currentWorker.isAlive())) {
            throw new ConcurrencyConflictException("Another scenario is currently executing or terminating. Max 1 active run permitted.");
        }

        boolean acquired;
        try {
            acquired = concurrencyGuard.tryAcquire(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyConflictException("Interrupted while acquiring execution permit");
        }

        if (!acquired) {
            throw new ConcurrencyConflictException("Another scenario acquired execution lock. Max 1 active run permitted.");
        }

        // 2. Initialize active run state
        UUID runId = UUID.randomUUID();
        Instant startTime = Instant.now();
        List<ScenarioStepEvent> liveTimeline = new CopyOnWriteArrayList<>();
        liveTimeline.add(ScenarioStepEvent.of("INIT", "Starting scenario: " + scenarioId.getKey()));

        ActiveRunState state = new ActiveRunState(runId, scenarioId, startTime, liveTimeline);
        activeRun.set(state);

        // 3. Dispatch async execution to coordinator executor
        executor.submit(() -> executeScenarioAsync(scenario, runId, startTime, liveTimeline));

        return LabRunView.inProgress(runId, scenarioId, ScenarioStatus.RUNNING, startTime, liveTimeline);
    }

    private void executeScenarioAsync(ChaosScenario scenario, UUID runId, Instant startTime, List<ScenarioStepEvent> timeline) {
        ScenarioEventSink sink = timeline::add;

        try {
            // Environment verification
            try {
                EnvironmentGuard.assertLabEnvironment(dataSource);
                sink.onStep(ScenarioStepEvent.of("ENVIRONMENT_VERIFIED", "Target database verified as safe lab/test environment"));
            } catch (SecurityException e) {
                log.error("Environment verification failed for run {}: {}", runId, e.getMessage());
                Instant endTime = Instant.now();
                ScenarioRunResult failResult = new ScenarioRunResult(
                        runId, scenario.getId(), ScenarioStatus.FAILED,
                        startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                        Collections.emptyList(), timeline, "Security abort: " + e.getMessage()
                );
                recordResult(failResult);
                return;
            }

            sink.onStep(ScenarioStepEvent.of("LOCK_ACQUIRED", "Acquired exclusive scenario runner lock"));

            AtomicReference<ScenarioRunResult> workerResult = new AtomicReference<>(null);
            AtomicReference<Throwable> workerError = new AtomicReference<>(null);

            Thread scenarioWorker = new Thread(() -> {
                try {
                    ScenarioRunResult res = scenario.execute(runId, dataSource, sink);
                    workerResult.set(res);
                } catch (Throwable t) {
                    workerError.set(t);
                }
            }, "chaos-worker-" + scenario.getId().name());

            activeWorkerThread.set(scenarioWorker);
            scenarioWorker.start();

            try {
                scenarioWorker.join(timeoutMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            ScenarioRunResult result;
            if (scenarioWorker.isAlive()) {
                scenarioWorker.interrupt();
                Instant endTime = Instant.now();
                sink.onStep(ScenarioStepEvent.of("TIMEOUT", "Scenario exceeded maximum duration: " + (timeoutMs / 1000) + " seconds"));
                result = new ScenarioRunResult(
                        runId, scenario.getId(), ScenarioStatus.TIMED_OUT,
                        startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                        Collections.emptyList(), timeline, "Scenario timed out after " + (timeoutMs / 1000) + " seconds"
                );
                recordResult(result);

                // Wait up to grace period for worker to complete cleanup/interruption
                try {
                    scenarioWorker.join(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else if (workerError.get() != null) {
                Throwable cause = workerError.get();
                log.error("Error executing scenario {}: {}", scenario.getId(), cause.getMessage(), cause);
                Instant endTime = Instant.now();
                sink.onStep(ScenarioStepEvent.of("ERROR", "Execution threw exception: " + cause.getMessage()));
                result = new ScenarioRunResult(
                        runId, scenario.getId(), ScenarioStatus.FAILED,
                        startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                        Collections.emptyList(), timeline, cause.getMessage()
                );
                recordResult(result);
            } else {
                result = workerResult.get();
                if (result == null) {
                    Instant endTime = Instant.now();
                    result = new ScenarioRunResult(
                            runId, scenario.getId(), ScenarioStatus.FAILED,
                            startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                            Collections.emptyList(), timeline, "Scenario produced null result"
                    );
                }
                recordResult(result);
            }

        } finally {
            Thread current = activeWorkerThread.get();
            if (current == null || !current.isAlive()) {
                activeRun.set(null);
                activeWorkerThread.set(null);
                concurrencyGuard.release();
            } else {
                log.warn("Scenario worker {} is still running after timeout. Retaining concurrency guard until termination.", current.getName());
                Thread cleanupMonitor = new Thread(() -> {
                    try {
                        current.join();
                    } catch (InterruptedException ignored) {
                    } finally {
                        activeRun.set(null);
                        activeWorkerThread.set(null);
                        if (concurrencyGuard.isLocked()) {
                            concurrencyGuard.release();
                        }
                        log.info("Zombied worker {} terminated. Concurrency guard released.", current.getName());
                    }
                }, "lab-worker-cleanup-monitor");
                cleanupMonitor.setDaemon(true);
                cleanupMonitor.start();
            }
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

    public Optional<LabRunView> getActiveRun() {
        ActiveRunState state = activeRun.get();
        if (state == null || runHistory.containsKey(state.runId)) {
            return Optional.empty();
        }
        return Optional.of(LabRunView.inProgress(
                state.runId,
                state.scenarioId,
                ScenarioStatus.RUNNING,
                state.startTime,
                new ArrayList<>(state.timeline)
        ));
    }

    public Optional<LabRunView> getRun(UUID runId) {
        ScenarioRunResult result = runHistory.get(runId);
        if (result != null) {
            return Optional.of(LabRunView.from(result));
        }
        ActiveRunState active = activeRun.get();
        if (active != null && active.runId.equals(runId)) {
            return Optional.of(LabRunView.inProgress(
                    active.runId,
                    active.scenarioId,
                    ScenarioStatus.RUNNING,
                    active.startTime,
                    new ArrayList<>(active.timeline)
            ));
        }
        return Optional.empty();
    }

    public List<LabRunView> getHistory(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_HISTORY_SIZE));
        List<LabRunView> list = new ArrayList<>();
        synchronized (this) {
            for (UUID id : historyOrder) {
                ScenarioRunResult r = runHistory.get(id);
                if (r != null) {
                    list.add(LabRunView.from(r));
                }
            }
        }
        Collections.reverse(list);
        if (list.size() > boundedLimit) {
            return list.subList(0, boundedLimit);
        }
        return list;
    }

    private record ActiveRunState(
            UUID runId,
            ScenarioId scenarioId,
            Instant startTime,
            List<ScenarioStepEvent> timeline
    ) {}
}
