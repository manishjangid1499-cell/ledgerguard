package com.ledgerguard.shared.tracing;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Phase 30 — Observability Isolation & Metric Cardinality Protection Tests")
class TraceCardinalityMetricsVerificationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Prometheus metrics endpoint does NOT leak trace IDs or correlation IDs into metric labels")
    void metricsEndpointDoesNotContainTraceOrCorrelationLabels() throws Exception {
        String testCorrelationId = "corr-probe-sentinel-" + UUID.randomUUID();
        String testTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + testTraceId + "-00f067aa0ba902b7-01";

        MvcResult result = mockMvc.perform(get("/actuator/prometheus")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, testCorrelationId)
                        .header("traceparent", traceparent))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, testCorrelationId))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).isNotNull();

        // Metrics body must NOT contain the correlation ID or trace ID in any tag or label
        assertThat(body).doesNotContain(testCorrelationId);
        assertThat(body).doesNotContain("traceId=\"" + testTraceId);
        assertThat(body).doesNotContain("correlationId=\"" + testCorrelationId);

        // Phase 29 integrity and business metrics remain intact
        assertThat(body).contains("unbalanced_journal_count");
        assertThat(body).contains("reconciliation_discrepancies");
        assertThat(body).contains("outbox_lag_seconds");
        assertThat(body).contains("duplicate_idempotency_keys");
    }
}