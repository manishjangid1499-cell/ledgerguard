package com.ledgerguard.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled worker that orchestrates periodic sampling of financial integrity metrics.
 */
@Component
public class IntegrityMetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(IntegrityMetricsSampler.class);

    private final IntegrityMetricsProperties properties;
    private final IntegrityMetricsSnapshotReader snapshotReader;
    private final IntegrityMetrics metrics;

    private final AtomicBoolean lastSampleFailed = new AtomicBoolean(false);

    public IntegrityMetricsSampler(IntegrityMetricsProperties properties,
                                   IntegrityMetricsSnapshotReader snapshotReader,
                                   IntegrityMetrics metrics) {
        this.properties = properties;
        this.snapshotReader = snapshotReader;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${ledgerguard.metrics.integrity.sample-interval:15s}",
            initialDelayString = "0"
    )
    public void sample() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        sampleNow();
    }

    /**
     * Synchronously executes one sampling pass.
     * Intentionally ignores schedulerEnabled so tests and manual callers can trigger on demand.
     */
    public void sampleNow() {
        try {
            IntegritySnapshot snapshot = snapshotReader.readSnapshot();
            metrics.updateSnapshot(snapshot);
            if (lastSampleFailed.getAndSet(false)) {
                log.info("Integrity metrics database sampling recovered");
            }
        } catch (Exception e) {
            boolean firstFailure = lastSampleFailed.compareAndSet(false, true);
            if (firstFailure) {
                log.warn("Integrity metrics database sampling failed; retaining previous snapshot (type={})",
                        e.getClass().getSimpleName());
            } else {
                log.debug("Integrity metrics database sampling remains unavailable (type={})",
                        e.getClass().getSimpleName());
            }
        }
    }

    public boolean isLastSampleFailed() {
        return lastSampleFailed.get();
    }
}