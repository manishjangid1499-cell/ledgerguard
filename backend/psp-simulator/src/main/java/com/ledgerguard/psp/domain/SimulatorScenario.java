package com.ledgerguard.psp.domain;

public enum SimulatorScenario {
    NORMAL_SUCCESS,
    TIMEOUT_AFTER_SUCCESS,
    DELAYED_WEBHOOK,
    DUPLICATE_WEBHOOK,
    TEMPORARY_500
}
