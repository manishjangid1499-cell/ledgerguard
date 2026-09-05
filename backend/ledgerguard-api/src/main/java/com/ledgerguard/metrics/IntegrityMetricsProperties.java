package com.ledgerguard.metrics;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "ledgerguard.metrics.integrity")
@Validated
public class IntegrityMetricsProperties {

    @NotNull
    private Duration sampleInterval = Duration.ofSeconds(15);

    private boolean schedulerEnabled = true;

    public Duration getSampleInterval() {
        return sampleInterval;
    }

    public void setSampleInterval(Duration sampleInterval) {
        if (sampleInterval != null && sampleInterval.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("sampleInterval must be strictly greater than Duration.ZERO");
        }
        this.sampleInterval = sampleInterval;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }
}