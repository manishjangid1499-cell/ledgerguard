package com.ledgerguard.lab.engine;

import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRunnerConcurrencyTest {

    @Test
    @DisplayName("ScenarioRunner permits only 1 concurrent scenario run and rejects overlapping runs")
    void preventsOverlappingRuns() throws Exception {
        ConcurrencyGuard guard = new ConcurrencyGuard();
        ScenarioRunner runner = new ScenarioRunner(guard);
        DataSource mockDs = createMockLabDataSource();

        CountDownLatch scenarioStartedLatch = new CountDownLatch(1);
        CountDownLatch finishScenarioLatch = new CountDownLatch(1);

        ChaosScenario blockingScenario = new ChaosScenario() {
            @Override
            public ScenarioId getId() {
                return ScenarioId.OPPOSING_TRANSFERS;
            }

            @Override
            public String getDescription() {
                return "Blocking test scenario";
            }

            @Override
            public ScenarioRunResult execute(UUID runId, DataSource dataSource) throws Exception {
                scenarioStartedLatch.countDown();
                finishScenarioLatch.await(30, TimeUnit.SECONDS);
                Instant now = Instant.now();
                return new ScenarioRunResult(
                        runId, getId(), ScenarioStatus.PASSED,
                        now, now, 0,
                        Collections.emptyList(), Collections.emptyList(), null
                );
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Run 1: starts and blocks
        Future<ScenarioRunResult> future1 = executor.submit(() -> runner.runScenario(blockingScenario, mockDs));
        assertThat(scenarioStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Run 2: attempted while Run 1 is active -> must be rejected by ConcurrencyGuard
        ChaosScenario secondScenario = new ChaosScenario() {
            @Override
            public ScenarioId getId() {
                return ScenarioId.WEBHOOK_RACE;
            }

            @Override
            public String getDescription() {
                return "Second scenario";
            }

            @Override
            public ScenarioRunResult execute(UUID runId, DataSource dataSource) {
                Instant now = Instant.now();
                return new ScenarioRunResult(runId, getId(), ScenarioStatus.PASSED, now, now, 0, Collections.emptyList(), Collections.emptyList(), null);
            }
        };

        ScenarioRunResult rejectedResult = runner.runScenario(secondScenario, mockDs, 1, TimeUnit.SECONDS);
        assertThat(rejectedResult.status()).isEqualTo(ScenarioStatus.FAILED);
        assertThat(rejectedResult.errorMessage()).contains("another run is in progress");

        // Release Run 1
        finishScenarioLatch.countDown();
        ScenarioRunResult result1 = future1.get(5, TimeUnit.SECONDS);
        assertThat(result1.status()).isEqualTo(ScenarioStatus.PASSED);

        // After Run 1 completes, lock is released and another run can succeed
        ScenarioRunResult result3 = runner.runScenario(secondScenario, mockDs);
        assertThat(result3.status()).isEqualTo(ScenarioStatus.PASSED);

        executor.shutdown();
    }

    private DataSource createMockLabDataSource() {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return createMockConnection();
                    }
                    return null;
                }
        );
    }

    private Connection createMockConnection() {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return createMockMetaData();
                    }
                    if ("getCatalog".equals(method.getName())) {
                        return "ledgerguard_lab_test";
                    }
                    return null;
                }
        );
    }

    private DatabaseMetaData createMockMetaData() {
        return (DatabaseMetaData) java.lang.reflect.Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getURL".equals(method.getName())) {
                        return "jdbc:postgresql://localhost:5432/ledgerguard_lab_test";
                    }
                    return null;
                }
        );
    }
}
