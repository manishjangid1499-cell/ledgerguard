package com.ledgerguard.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IntegrityMetrics â€” Atomic publication and counter unit tests")
class IntegrityMetricsUnitTest {

    private MeterRegistry registry;
    private IntegrityMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new IntegrityMetrics(registry);
    }

    @Test
    @DisplayName("Pre-first-sample state: all three DB-backed gauges expose Double.NaN")
    void preFirstSampleGaugesAreNaN() {
        assertThat(metrics.getLatestSnapshot()).isNull();

        Gauge unbalanced = registry.find(IntegrityMetrics.UNBALANCED_JOURNAL_COUNT).gauge();
        Gauge recon = registry.find(IntegrityMetrics.RECONCILIATION_DISCREPANCIES).gauge();
        Gauge outbox = registry.find(IntegrityMetrics.OUTBOX_LAG_SECONDS).gauge();

        assertThat(unbalanced).isNotNull();
        assertThat(recon).isNotNull();
        assertThat(outbox).isNotNull();

        assertThat(unbalanced.value()).isNaN();
        assertThat(recon.value()).isNaN();
        assertThat(outbox.value()).isNaN();
    }

    @Test
    @DisplayName("Duplicate idempotency counters are eagerly registered at 0.0")
    void duplicateCountersEagerlyRegistered() {
        Counter replay = registry.find(IntegrityMetrics.DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", IntegrityMetrics.REASON_REPLAY).counter();
        Counter conflict = registry.find(IntegrityMetrics.DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", IntegrityMetrics.REASON_FINGERPRINT_CONFLICT).counter();
        Counter inProgress = registry.find(IntegrityMetrics.DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", IntegrityMetrics.REASON_IN_PROGRESS).counter();

        assertThat(replay).isNotNull();
        assertThat(conflict).isNotNull();
        assertThat(inProgress).isNotNull();

        assertThat(replay.count()).isEqualTo(0.0);
        assertThat(conflict.count()).isEqualTo(0.0);
        assertThat(inProgress.count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Atomic snapshot publication updates all gauges simultaneously")
    void atomicSnapshotPublication() {
        metrics.updateSnapshot(new IntegritySnapshot(3, 4, 5.5));

        assertThat(registry.find(IntegrityMetrics.UNBALANCED_JOURNAL_COUNT).gauge().value()).isEqualTo(3.0);
        assertThat(registry.find(IntegrityMetrics.RECONCILIATION_DISCREPANCIES).gauge().value()).isEqualTo(4.0);
        assertThat(registry.find(IntegrityMetrics.OUTBOX_LAG_SECONDS).gauge().value()).isEqualTo(5.5);

        // Subsequent atomic update
        metrics.updateSnapshot(new IntegritySnapshot(7, 8, 9.5));

        assertThat(registry.find(IntegrityMetrics.UNBALANCED_JOURNAL_COUNT).gauge().value()).isEqualTo(7.0);
        assertThat(registry.find(IntegrityMetrics.RECONCILIATION_DISCREPANCIES).gauge().value()).isEqualTo(8.0);
        assertThat(registry.find(IntegrityMetrics.OUTBOX_LAG_SECONDS).gauge().value()).isEqualTo(9.5);
    }

    @Test
    @DisplayName("Duplicate counter increments are isolated per reason")
    void duplicateCounterIncrements() {
        metrics.incrementReplay();
        metrics.incrementReplay();
        metrics.incrementFingerprintConflict();
        metrics.incrementInProgress();

        assertThat(registry.find(IntegrityMetrics.DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", IntegrityMetrics.REASON_REPLAY).counter().count()).isEqualTo(2.0);
        assertThat(registry.find(IntegrityMetrics.DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", IntegrityMetrics.REASON_FINGERPRINT_CONFLICT).counter().count()).isEqualTo(1.0);
        assertThat(registry.find(IntegrityMetrics.DUPLICATE_IDEMPOTENCY_KEYS)
                .tag("reason", IntegrityMetrics.REASON_IN_PROGRESS).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("IntegrityMetrics exposes no separate public individual gauge setters")
    void noSeparateGaugeSetters() {
        Method[] methods = IntegrityMetrics.class.getDeclaredMethods();
        for (Method method : methods) {
            assertThat(method.getName())
                    .doesNotContain("setUnbalanced")
                    .doesNotContain("setReconciliation")
                    .doesNotContain("setOutbox");
        }
    }
}