package com.ledgerguard.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IntegrityMetricsProperties â€” Unit and validation tests")
class IntegrityMetricsPropertiesValidationTest {

    @Test
    @DisplayName("Default properties: 15s interval, scheduler enabled")
    void defaultValues() {
        IntegrityMetricsProperties props = new IntegrityMetricsProperties();
        assertThat(props.getSampleInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(props.isSchedulerEnabled()).isTrue();
    }

    @Test
    @DisplayName("Valid interval: 30s accepted")
    void validIntervalAccepted() {
        IntegrityMetricsProperties props = new IntegrityMetricsProperties();
        props.setSampleInterval(Duration.ofSeconds(30));
        assertThat(props.getSampleInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("Invalid interval: 0s throws IllegalArgumentException")
    void zeroIntervalRejected() {
        IntegrityMetricsProperties props = new IntegrityMetricsProperties();
        assertThatThrownBy(() -> props.setSampleInterval(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly greater than Duration.ZERO");
    }

    @Test
    @DisplayName("Invalid interval: negative duration throws IllegalArgumentException")
    void negativeIntervalRejected() {
        IntegrityMetricsProperties props = new IntegrityMetricsProperties();
        assertThatThrownBy(() -> props.setSampleInterval(Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly greater than Duration.ZERO");
    }

    @Test
    @DisplayName("Scheduler can be toggled")
    void schedulerToggle() {
        IntegrityMetricsProperties props = new IntegrityMetricsProperties();
        props.setSchedulerEnabled(false);
        assertThat(props.isSchedulerEnabled()).isFalse();
    }
}