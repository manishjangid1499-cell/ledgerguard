package com.ledgerguard.lab.engine;

import com.ledgerguard.lab.FailureLabApplication;
import com.ledgerguard.lab.api.dto.LabRunView;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;
import com.ledgerguard.lab.model.ScenarioStepEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("LabRunCoordinator Engine & Runtime Safety Tests")
class LabRunCoordinatorTest {

    private ScenarioRegistry registry;
    private DataSource dataSource;
    private ConcurrencyGuard concurrencyGuard;
    private ExecutorService executor;
    private LabRunCoordinator coordinator;

    @BeforeEach
    void setUp() throws Exception {
        registry = new ScenarioRegistry();
        dataSource = Mockito.mock(DataSource.class);
        Connection conn = Mockito.mock(Connection.class);
        DatabaseMetaData meta = Mockito.mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getURL()).thenReturn("jdbc:postgresql://localhost:5432/test_lab_db");

        concurrencyGuard = new ConcurrencyGuard();
        executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                r -> new Thread(r, "test-lab-coordinator"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        coordinator = new LabRunCoordinator(registry, dataSource, concurrencyGuard, executor, 30_000L);
    }

    @Test
    @DisplayName("Starting run emits RUNNING status and registers active run")
    void testStartRunSuccess() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ChaosScenario scenario = Mockito.mock(ChaosScenario.class);
        when(scenario.getId()).thenReturn(ScenarioId.OPPOSING_TRANSFERS);
        when(scenario.execute(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            latch.await(2, TimeUnit.SECONDS);
            Instant now = Instant.now();
            return new ScenarioRunResult(
                    inv.getArgument(0), ScenarioId.OPPOSING_TRANSFERS, ScenarioStatus.PASSED,
                    now, now, 10L, Collections.emptyList(), Collections.emptyList(), null
            );
        });

        registry.register(scenario);

        LabRunView view = coordinator.startRun(ScenarioId.OPPOSING_TRANSFERS);
        assertThat(view).isNotNull();
        assertThat(view.scenarioId()).isEqualTo(ScenarioId.OPPOSING_TRANSFERS);
        assertThat(view.status()).isEqualTo(ScenarioStatus.RUNNING);

        Optional<LabRunView> active = coordinator.getActiveRun();
        assertThat(active).isPresent();
        assertThat(active.get().runId()).isEqualTo(view.runId());

        // Concurrency guard is engaged
        assertThat(concurrencyGuard.isLocked()).isTrue();

        // Second start attempt fails with ConcurrencyConflictException
        assertThatThrownBy(() -> coordinator.startRun(ScenarioId.OPPOSING_TRANSFERS))
                .isInstanceOf(ConcurrencyConflictException.class);

        latch.countDown();
        // Wait for execution to complete
        Thread.sleep(150);

        // After completion, active run clears and result moves to history
        assertThat(coordinator.getActiveRun()).isEmpty();
        assertThat(coordinator.getRun(view.runId())).isPresent();
        assertThat(coordinator.getRun(view.runId()).get().status()).isEqualTo(ScenarioStatus.PASSED);
        assertThat(concurrencyGuard.isLocked()).isFalse();
    }

    @Test
    @DisplayName("Concurrent start requests: exactly 1 accepted, 1 rejected with 409 conflict")
    void testAtomicAdmissionConcurrency() throws Exception {
        CountDownLatch scenarioBlocker = new CountDownLatch(1);
        CountDownLatch startBarrier = new CountDownLatch(1);

        ChaosScenario scenario = Mockito.mock(ChaosScenario.class);
        when(scenario.getId()).thenReturn(ScenarioId.OPPOSING_TRANSFERS);
        when(scenario.execute(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            scenarioBlocker.await(5, TimeUnit.SECONDS);
            Instant now = Instant.now();
            return new ScenarioRunResult(
                    inv.getArgument(0), ScenarioId.OPPOSING_TRANSFERS, ScenarioStatus.PASSED,
                    now, now, 10L, Collections.emptyList(), Collections.emptyList(), null
            );
        });
        registry.register(scenario);

        ExecutorService clientPool = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Runnable startTask = () -> {
            try {
                startBarrier.await();
                coordinator.startRun(ScenarioId.OPPOSING_TRANSFERS);
                successCount.incrementAndGet();
            } catch (ConcurrencyConflictException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                // unexpected
            }
        };

        Future<?> f1 = clientPool.submit(startTask);
        Future<?> f2 = clientPool.submit(startTask);

        startBarrier.countDown();
        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        scenarioBlocker.countDown();
        Thread.sleep(150);
        clientPool.shutdown();
    }

    @Test
    @DisplayName("Timeout safety: second run rejected with 409 while timed-out worker is still alive; accepted after worker exits")
    void testTimeoutSafetyNoOverlap() throws Exception {
        // Coordinator with 150ms timeout
        LabRunCoordinator shortTimeoutCoordinator = new LabRunCoordinator(
                registry, dataSource, new ConcurrencyGuard(), executor, 150L
        );

        java.util.concurrent.atomic.AtomicBoolean workerRunning = new java.util.concurrent.atomic.AtomicBoolean(true);

        ChaosScenario scenario = Mockito.mock(ChaosScenario.class);
        when(scenario.getId()).thenReturn(ScenarioId.TIMEOUT_AFTER_COMMIT);
        when(scenario.execute(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            while (workerRunning.get()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    // Scenario handles or simulates transactional cleanup
                }
            }
            Instant now = Instant.now();
            return new ScenarioRunResult(
                    inv.getArgument(0), ScenarioId.TIMEOUT_AFTER_COMMIT, ScenarioStatus.PASSED,
                    now, now, 10L, Collections.emptyList(), Collections.emptyList(), null
            );
        });
        registry.register(scenario);

        LabRunView run1 = shortTimeoutCoordinator.startRun(ScenarioId.TIMEOUT_AFTER_COMMIT);

        // Wait for 150ms timeout to trigger
        Thread.sleep(300);

        // Run 1 status must be TIMED_OUT in history
        Optional<LabRunView> view1 = shortTimeoutCoordinator.getRun(run1.runId());
        assertThat(view1).isPresent();
        assertThat(view1.get().status()).isEqualTo(ScenarioStatus.TIMED_OUT);

        // Crucial safety check: While the worker thread is still alive in the background,
        // any subsequent start request MUST be rejected with ConcurrencyConflictException!
        assertThatThrownBy(() -> shortTimeoutCoordinator.startRun(ScenarioId.TIMEOUT_AFTER_COMMIT))
                .isInstanceOf(ConcurrencyConflictException.class);

        // Signal worker thread that cleanup is complete
        workerRunning.set(false);
        Thread.sleep(200);

        // Now that the old worker has genuinely stopped and exited, the next run can start safely
        LabRunView run2 = shortTimeoutCoordinator.startRun(ScenarioId.TIMEOUT_AFTER_COMMIT);
        assertThat(run2).isNotNull();
        assertThat(run2.runId()).isNotEqualTo(run1.runId());
        assertThat(run2.status()).isEqualTo(ScenarioStatus.RUNNING);
    }

    @Test
    @DisplayName("Live timeline streaming: intermediate events visible before completion")
    void testLiveTimelineStreaming() throws Exception {
        CountDownLatch step1Latch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        ChaosScenario scenario = Mockito.mock(ChaosScenario.class);
        when(scenario.getId()).thenReturn(ScenarioId.CORRUPTED_SNAPSHOT);
        when(scenario.execute(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            ScenarioEventSink sink = inv.getArgument(2);
            sink.onStep(ScenarioStepEvent.of("FAULT_INJECTED", "Corrupted balance snapshot"));
            step1Latch.countDown();
            finishLatch.await(2, TimeUnit.SECONDS);
            Instant now = Instant.now();
            return new ScenarioRunResult(
                    inv.getArgument(0), ScenarioId.CORRUPTED_SNAPSHOT, ScenarioStatus.PASSED,
                    now, now, 10L, Collections.emptyList(), Collections.emptyList(), null
            );
        });
        registry.register(scenario);

        LabRunView view = coordinator.startRun(ScenarioId.CORRUPTED_SNAPSHOT);
        step1Latch.await(2, TimeUnit.SECONDS);

        // Poll active run while still executing
        Optional<LabRunView> active = coordinator.getActiveRun();
        assertThat(active).isPresent();
        assertThat(active.get().status()).isEqualTo(ScenarioStatus.RUNNING);
        List<ScenarioStepEvent> timeline = active.get().timeline();
        assertThat(timeline).anyMatch(e -> e.stepName().equals("FAULT_INJECTED"));

        finishLatch.countDown();
        Thread.sleep(150);
        assertThat(coordinator.getActiveRun()).isEmpty();
    }

    @Test
    @DisplayName("History bounds: newest-first ordering and clamped max limit")
    void testHistoryBoundsAndOrdering() throws Exception {
        ChaosScenario scenario = Mockito.mock(ChaosScenario.class);
        when(scenario.getId()).thenReturn(ScenarioId.WEBHOOK_RACE);
        when(scenario.execute(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            Instant now = Instant.now();
            return new ScenarioRunResult(
                    inv.getArgument(0), ScenarioId.WEBHOOK_RACE, ScenarioStatus.PASSED,
                    now, now, 5L, Collections.emptyList(), Collections.emptyList(), null
            );
        });
        registry.register(scenario);

        // Run 3 sequential scenarios
        for (int i = 0; i < 3; i++) {
            LabRunView v = coordinator.startRun(ScenarioId.WEBHOOK_RACE);
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {}
        }

        List<LabRunView> history = coordinator.getHistory(10);
        assertThat(history).hasSize(3);
        // Newest first: first element should have latest startedAt
        assertThat(history.get(0).startedAt()).isAfterOrEqualTo(history.get(1).startedAt());
        assertThat(history.get(1).startedAt()).isAfterOrEqualTo(history.get(2).startedAt());

        // Bounded limit test
        List<LabRunView> clamped = coordinator.getHistory(2);
        assertThat(clamped).hasSize(2);
    }
}
