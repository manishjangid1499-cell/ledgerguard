package com.ledgerguard.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("IntegrityMetricsSampler — Unit and failure transition tests")
class IntegrityMetricsSamplerUnitTest {

    private IntegrityMetricsProperties properties;
    private IntegrityMetricsSnapshotReader snapshotReader;
    private IntegrityMetrics metrics;
    private IntegrityMetricsSampler sampler;

    @BeforeEach
    void setUp() {
        properties = new IntegrityMetricsProperties();
        snapshotReader = mock(IntegrityMetricsSnapshotReader.class);
        metrics = new IntegrityMetrics(new SimpleMeterRegistry());
        sampler = new IntegrityMetricsSampler(properties, snapshotReader, metrics);
    }

    @Test
    @DisplayName("When scheduler is disabled, sample() does not invoke snapshotReader")
    void schedulerDisabledSuppressesScheduledRun() {
        properties.setSchedulerEnabled(false);

        sampler.sample();

        verify(snapshotReader, never()).readSnapshot();
        assertThat(metrics.getLatestSnapshot()).isNull();
    }

    @Test
    @DisplayName("sampleNow() ignores schedulerEnabled flag and executes sampling")
    void sampleNowIgnoresSchedulerDisabled() {
        properties.setSchedulerEnabled(false);
        when(snapshotReader.readSnapshot()).thenReturn(new IntegritySnapshot(1, 2, 3.0));

        sampler.sampleNow();

        verify(snapshotReader, times(1)).readSnapshot();
        assertThat(metrics.getLatestSnapshot()).isEqualTo(new IntegritySnapshot(1, 2, 3.0));
    }

    @Test
    @DisplayName("Failure transitions: pre-sample NaN, failure retains last-known value, recovery clears failure state")
    void failureAndRecoveryTransitions() {
        // 1. Initial state: NaN
        assertThat(metrics.getLatestSnapshot()).isNull();

        // 2. Successful first sample
        when(snapshotReader.readSnapshot()).thenReturn(new IntegritySnapshot(0, 0, 0.0));
        sampler.sampleNow();
        assertThat(metrics.getLatestSnapshot()).isEqualTo(new IntegritySnapshot(0, 0, 0.0));
        assertThat(sampler.isLastSampleFailed()).isFalse();

        // 3. Reader failure: previous successful snapshot is retained
        when(snapshotReader.readSnapshot()).thenThrow(new QueryTimeoutException("Simulated timeout"));
        sampler.sampleNow();
        assertThat(metrics.getLatestSnapshot()).isEqualTo(new IntegritySnapshot(0, 0, 0.0)); // Retained!
        assertThat(sampler.isLastSampleFailed()).isTrue();

        // 4. Repeated failure: still retained
        sampler.sampleNow();
        assertThat(metrics.getLatestSnapshot()).isEqualTo(new IntegritySnapshot(0, 0, 0.0));
        assertThat(sampler.isLastSampleFailed()).isTrue();

        // 5. Recovery: new snapshot replaces old, failure flag cleared
        doReturn(new IntegritySnapshot(2, 1, 10.0)).when(snapshotReader).readSnapshot();
        sampler.sampleNow();
        assertThat(metrics.getLatestSnapshot()).isEqualTo(new IntegritySnapshot(2, 1, 10.0));
        assertThat(sampler.isLastSampleFailed()).isFalse();
    }

    @Test
    @DisplayName("Sensitive exception message sentinel is never logged")
    void sensitiveExceptionMessageNotLogged(CapturedOutput output) {
        when(snapshotReader.readSnapshot()).thenThrow(new RuntimeException("DO_NOT_LOG_DATABASE_DETAIL"));
        sampler.sampleNow();

        assertThat(output.getAll()).doesNotContain("DO_NOT_LOG_DATABASE_DETAIL");
        assertThat(output.getAll()).contains("Integrity metrics database sampling failed; retaining previous snapshot (type=RuntimeException)");
    }

    @Test
    @DisplayName("Transition-aware logging: 3 failures -> 1 WARN, 1 recovery -> 1 INFO, second outage -> 2 total WARN")
    void transitionAwareLogging(CapturedOutput output) {
        when(snapshotReader.readSnapshot()).thenThrow(new RuntimeException("Simulated error"));

        // 3 consecutive failures
        sampler.sampleNow();
        sampler.sampleNow();
        sampler.sampleNow();

        long warnCountAfterThreeFailures = output.getAll().lines()
                .filter(l -> l.contains("Integrity metrics database sampling failed; retaining previous snapshot"))
                .count();
        assertThat(warnCountAfterThreeFailures).isEqualTo(1);

        // 1 recovery
        doReturn(new IntegritySnapshot(0, 0, 0.0)).when(snapshotReader).readSnapshot();
        sampler.sampleNow();

        long infoRecoveryCount = output.getAll().lines()
                .filter(l -> l.contains("Integrity metrics database sampling recovered"))
                .count();
        assertThat(infoRecoveryCount).isEqualTo(1);

        // Next failure (new failure transition)
        doThrow(new RuntimeException("Second outage")).when(snapshotReader).readSnapshot();
        sampler.sampleNow();

        long totalWarnCount = output.getAll().lines()
                .filter(l -> l.contains("Integrity metrics database sampling failed; retaining previous snapshot"))
                .count();
        assertThat(totalWarnCount).isEqualTo(2);
    }
}