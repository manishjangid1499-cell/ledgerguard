package com.ledgerguard.lab.engine;

import com.ledgerguard.lab.AbstractFailureLabIntegrationTest;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;
import com.ledgerguard.lab.scenarios.RealCorruptedSnapshotScenario;
import com.ledgerguard.lab.scenarios.RealOpposingTransfersScenario;
import com.ledgerguard.lab.scenarios.RealTimeoutAfterCommitScenario;
import com.ledgerguard.lab.scenarios.RealWebhookRaceScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure Lab Suite Runner (All 4 Real Scenarios)")
class FailureLabSuiteRunnerTest extends AbstractFailureLabIntegrationTest {

    @Test
    @DisplayName("FailureLabSuiteRunner executes all 4 real production chaos scenarios sequentially")
    void executesAllScenarios() throws Exception {
        ScenarioRegistry registry = new ScenarioRegistry();
        ScenarioRunner runner = new ScenarioRunner();

        // Register the 4 real scenarios executing production application logic
        registry.register(new RealOpposingTransfersScenario(
                transferService, ledgerPostingService, userRepository, ledgerAccountRepository
        ));
        registry.register(new RealTimeoutAfterCommitScenario(
                payoutService, pollingService, payoutRepository, balanceHoldRepository,
                userRepository, ledgerAccountRepository, ledgerPostingService, jdbcTemplate, HTTP_PROVIDER_ADAPTER
        ));
        registry.register(new RealCorruptedSnapshotScenario(
                snapshotConsistencyChecker, autoRepairService, userRepository,
                ledgerAccountRepository, ledgerPostingService, jdbcTemplate, LAB_TARGET
        ));
        registry.register(new RealWebhookRaceScenario(
                webhookController, fundingOperationRepository, userRepository,
                ledgerAccountRepository, RUNTIME_WEBHOOK_SECRET
        ));

        Map<ScenarioId, ChaosScenario> scenarios = registry.getAll();
        assertThat(scenarios).hasSize(4);

        List<ScenarioRunResult> results = new ArrayList<>();
        for (ChaosScenario scenario : scenarios.values()) {
            ScenarioRunResult result = runner.runScenario(scenario, dataSource);
            results.add(result);
        }

        // When financial-failure-ci is enabled, generate invariant reports before final assertions
        if (Boolean.getBoolean("financial.failure.ci")) {
            FinancialInvariantReportGenerator.generate(results);
        }

        assertThat(results).hasSize(4);
        for (ScenarioRunResult result : results) {
            assertThat(result.status())
                    .as("Scenario %s must PASS", result.scenarioId().getKey())
                    .isEqualTo(ScenarioStatus.PASSED);
            assertThat(result.isAllInvariantsPassed())
                    .as("Scenario %s all invariants must pass", result.scenarioId().getKey())
                    .isTrue();
            assertThat(result.errorMessage()).isNull();
        }

        assertThat(runner.getHistory()).hasSize(4);
    }
}
