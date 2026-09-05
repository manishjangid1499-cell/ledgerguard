package com.ledgerguard.shared.tracing;

import com.ledgerguard.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "ledgerguard.security.cors.allowed-origins=http://localhost:5173"
})
@DisplayName("Phase 30 — Correlation ID Security Integration Tests")
class CorrelationIdSecurityIntegrationTest extends AbstractIntegrationTest {

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
    @DisplayName("Unauthenticated request (401 Unauthorized) emits X-Correlation-Id header")
    void unauthenticatedRequestReturnsCorrelationId() throws Exception {
        mockMvc.perform(get("/api/wallets/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("Public actuator health and prometheus endpoints emit X-Correlation-Id header")
    void actuatorEndpointsReturnCorrelationId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("Preserves valid inbound X-Correlation-Id on authenticated or unauthenticated calls")
    void echoesClientSuppliedCorrelationId() throws Exception {
        String clientCorrelationId = "test-corr-" + UUID.randomUUID();
        mockMvc.perform(get("/actuator/health")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, clientCorrelationId))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, clientCorrelationId));
    }

    @Test
    @DisplayName("Sanitizes invalid inbound X-Correlation-Id with invalid characters")
    void sanitizesMalformedCorrelationId() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "invalid@@##$$%%injection"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, Matchers.not("invalid@@##$$%%injection")))
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, Matchers.matchesRegex("^[0-9a-fA-F-]{36}$")));
    }

    @Test
    @DisplayName("CORS preflight exposes X-Correlation-Id and allows X-Correlation-Id header")
    void corsPreflightExposesCorrelationId() throws Exception {
        mockMvc.perform(options("/api/transfers")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type,X-Correlation-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers", Matchers.containsString(CorrelationIdFilter.CORRELATION_ID_HEADER)))
                .andExpect(header().string("Access-Control-Allow-Headers", Matchers.containsString(CorrelationIdFilter.CORRELATION_ID_HEADER)));
    }
}