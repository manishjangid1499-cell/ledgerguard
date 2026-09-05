package com.ledgerguard.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns in-memory metric holders and registers Micrometer gauges and counters.
 * <p>
 * Database-backed gauges read atomically from a single {@link AtomicReference}
 * which is null before the first successful database sample (exposing Double.NaN).
 */
@Component
public class IntegrityMetrics {

    public static final String UNBALANCED_JOURNAL_COUNT = "unbalanced_journal_count";
    public static final String RECONCILIATION_DISCREPANCIES = "reconciliation_discrepancies";
    public static final String OUTBOX_LAG_SECONDS = "outbox_lag_seconds";
    public static final String DUPLICATE_IDEMPOTENCY_KEYS = "duplicate_idempotency_keys";

    public static final String REASON_REPLAY = "replay";
    public static final String REASON_FINGERPRINT_CONFLICT = "fingerprint_conflict";
    public static final String REASON_IN_PROGRESS = "in_progress";

    private final AtomicReference<IntegritySnapshot> latestSnapshot = new AtomicReference<>(null);

    private final Counter replayCounter;
    private final Counter fingerprintConflictCounter;
    private final Counter inProgressCounter;

    public IntegrityMetrics(MeterRegistry registry) {
        // Register Gauges reading latestSnapshot
        Gauge.builder(UNBALANCED_JOURNAL_COUNT, () -> {
                    IntegritySnapshot s = latestSnapshot.get();
                    return s == null ? Double.NaN : (double) s.unbalancedJournalCount();
                })
                .description("Current count of POSTED journal transactions violating balance or posting structure")
                .register(registry);

        Gauge.builder(RECONCILIATION_DISCREPANCIES, () -> {
                    IntegritySnapshot s = latestSnapshot.get();
                    return s == null ? Double.NaN : (double) s.reconciliationDiscrepancies();
                })
                .description("Current count of active reconciliation review cases classified as discrepancy")
                .register(registry);

        Gauge.builder(OUTBOX_LAG_SECONDS, () -> {
                    IntegritySnapshot s = latestSnapshot.get();
                    return s == null ? Double.NaN : s.outboxLagSeconds();
                })
                .description("Age in seconds of the oldest pending outbox event")
                .register(registry);

        // Eagerly register the three duplicate idempotency counters (reason=replay, fingerprint_conflict, in_progress)
        this.replayCounter = Counter.builder(DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", REASON_REPLAY)
                .description("Application-observed duplicate idempotency-key encounters")
                .register(registry);

        this.fingerprintConflictCounter = Counter.builder(DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", REASON_FINGERPRINT_CONFLICT)
                .description("Application-observed duplicate idempotency-key encounters")
                .register(registry);

        this.inProgressCounter = Counter.builder(DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", REASON_IN_PROGRESS)
                .description("Application-observed duplicate idempotency-key encounters")
                .register(registry);
    }

    /**
     * Atomically publishes a new coherent snapshot from the database reader.
     */
    public void updateSnapshot(IntegritySnapshot snapshot) {
        latestSnapshot.set(Objects.requireNonNull(snapshot, "snapshot must not be null"));
    }

    public IntegritySnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }

    public void incrementReplay() {
        replayCounter.increment();
    }

    public void incrementFingerprintConflict() {
        fingerprintConflictCounter.increment();
    }

    public void incrementInProgress() {
        inProgressCounter.increment();
    }
}