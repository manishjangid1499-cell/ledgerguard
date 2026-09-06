package com.ledgerguard.lab.engine;

import com.ledgerguard.lab.model.ScenarioStepEvent;

/**
 * Functional sink interface for receiving real-time scenario progress step events.
 */
@FunctionalInterface
public interface ScenarioEventSink {

    /**
     * Called when a scenario emits a timeline step event.
     *
     * @param event emitted step event
     */
    void onStep(ScenarioStepEvent event);

    /**
     * No-op sink when real-time emission is not required.
     */
    ScenarioEventSink NO_OP = event -> {};
}
