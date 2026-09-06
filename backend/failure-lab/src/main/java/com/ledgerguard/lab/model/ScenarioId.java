package com.ledgerguard.lab.model;

/**
 * Identifiers for authoritative Money Integrity Failure Lab chaos scenarios.
 */
public enum ScenarioId {
    OPPOSING_TRANSFERS("opposing-transfers", "Concurrent bidirectional transfers stressing lock ordering and deadlock freedom"),
    TIMEOUT_AFTER_COMMIT("timeout-after-commit", "PSP physical timeout after commit proving UNKNOWN != FAILED and exactly-once settlement"),
    CORRUPTED_SNAPSHOT("corrupted-snapshot", "Intentional snapshot corruption detected by Level 2 reconciliation and repaired from immutable journals"),
    WEBHOOK_RACE("webhook-race", "Concurrent and duplicate signed webhooks proving idempotency, deduplication, and single economic effect");

    private final String key;
    private final String description;

    ScenarioId(String key, String description) {
        this.key = key;
        this.description = description;
    }

    public String getKey() {
        return key;
    }

    public String getDescription() {
        return description;
    }

    public static ScenarioId fromKey(String key) {
        for (ScenarioId id : values()) {
            if (id.key.equalsIgnoreCase(key) || id.name().equalsIgnoreCase(key)) {
                return id;
            }
        }
        throw new IllegalArgumentException("Unknown scenario ID: " + key);
    }
}
