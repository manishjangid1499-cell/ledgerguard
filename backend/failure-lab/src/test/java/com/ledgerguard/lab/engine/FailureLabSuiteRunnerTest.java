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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure Lab Suite Runner (All 4 Real Scenarios)")
class FailureLabSuiteRunnerTest extends AbstractFailureLabIntegrationTest {

    @Test
    @DisplayName("FailureLabSuiteRunner executes all 4 real production chaos scenarios sequentially")
    void executesAllScenarios() {
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

        for (ChaosScenario scenario : scenarios.values()) {
            ScenarioRunResult result = runner.runScenario(scenario, dataSource);
            assertThat(result.status())
                    .as("Scenario %s must PASS", scenario.getId().getKey())
                    .isEqualTo(ScenarioStatus.PASSED);
            assertThat(result.isAllInvariantsPassed()).isTrue();
            assertThat(result.errorMessage()).isNull();
        }

        assertThat(runner.getHistory()).hasSize(4);
    }
}
