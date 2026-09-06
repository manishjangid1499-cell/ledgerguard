package com.ledgerguard.lab.engine;

import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Contract for a programmatic Money Integrity chaos scenario.
 */
public interface ChaosScenario {

    ScenarioId getId();

    String getDescription();

    /**
     * Executes the chaos scenario against the provided verified lab DataSource.
     *
     * @param runId unique identifier for this run
     * @param dataSource target lab database
     * @return structured scenario execution result
     * @throws Exception if unexpected runtime failure occurs
     */
    default ScenarioRunResult execute(UUID runId, DataSource dataSource) throws Exception {
        return execute(runId, dataSource, ScenarioEventSink.NO_OP);
    }

    /**
     * Executes the chaos scenario against the provided verified lab DataSource with an incremental event sink.
     *
     * @param runId unique identifier for this run
     * @param dataSource target lab database
     * @param sink real-time event sink for emitting timeline progress
     * @return structured scenario execution result
     * @throws Exception if unexpected runtime failure occurs
     */
    default ScenarioRunResult execute(UUID runId, DataSource dataSource, ScenarioEventSink sink) throws Exception {
        return execute(runId, dataSource);
    }
}
