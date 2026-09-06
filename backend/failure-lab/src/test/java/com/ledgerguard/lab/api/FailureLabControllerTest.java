package com.ledgerguard.lab.api;

import com.ledgerguard.lab.api.dto.LabRunView;
import com.ledgerguard.lab.engine.ConcurrencyConflictException;
import com.ledgerguard.lab.engine.LabRunCoordinator;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioStatus;
import com.ledgerguard.lab.model.ScenarioStepEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Failure Lab REST Controller Tests")
class FailureLabControllerTest {

    private LabRunCoordinator coordinator;
    private MockMvc mockMvc;
    private MockMvc mockMvcDisabled;

    @BeforeEach
    void setUp() {
        coordinator = Mockito.mock(LabRunCoordinator.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FailureLabController(coordinator, true)).build();
        mockMvcDisabled = MockMvcBuilders.standaloneSetup(new FailureLabController(coordinator, false)).build();
    }

    @Test
    @DisplayName("GET /api/lab/environment returns 200 and READY when enabled")
    void testGetEnvironmentEnabled() throws Exception {
        mockMvc.perform(get("/api/lab/environment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.postgresStatus").value("UP"))
                .andExpect(jsonPath("$.kafkaStatus").value("UP"))
                .andExpect(jsonPath("$.pspAdapterStatus").value("UP"))
                .andExpect(jsonPath("$.mode").value("EPHEMERAL_TESTCONTAINERS"));
    }

    @Test
    @DisplayName("GET /api/lab/environment returns 503 and DISABLED when lab disabled")
    void testGetEnvironmentDisabled() throws Exception {
        mockMvcDisabled.perform(get("/api/lab/environment"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    @DisplayName("GET /api/lab/scenarios returns all 4 registered scenarios")
    void testGetScenarios() throws Exception {
        mockMvc.perform(get("/api/lab/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value("OPPOSING_TRANSFERS"))
                .andExpect(jsonPath("$[1].id").value("TIMEOUT_AFTER_COMMIT"))
                .andExpect(jsonPath("$[2].id").value("CORRUPTED_SNAPSHOT"))
                .andExpect(jsonPath("$[3].id").value("WEBHOOK_RACE"));
    }

    @Test
    @DisplayName("POST /api/lab/runs returns 202 Accepted when run successfully launched")
    void testStartRunAccepted() throws Exception {
        UUID runId = UUID.randomUUID();
        LabRunView view = LabRunView.inProgress(
                runId, ScenarioId.OPPOSING_TRANSFERS, ScenarioStatus.RUNNING, Instant.now(),
                List.of(ScenarioStepEvent.of("INIT", "Starting scenario"))
        );
        when(coordinator.startRun(ScenarioId.OPPOSING_TRANSFERS)).thenReturn(view);

        mockMvc.perform(post("/api/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"OPPOSING_TRANSFERS\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.scenarioId").value("OPPOSING_TRANSFERS"));
    }

    @Test
    @DisplayName("POST /api/lab/runs returns 409 Conflict when another scenario is active")
    void testStartRunConflict() throws Exception {
        when(coordinator.startRun(any())).thenThrow(new ConcurrencyConflictException("Another scenario is currently running. Max 1 active run permitted."));

        mockMvc.perform(post("/api/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"OPPOSING_TRANSFERS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Another scenario is currently running. Max 1 active run permitted."));
    }

    @Test
    @DisplayName("GET /api/lab/runs/active returns 200 when active run exists")
    void testGetActiveRunPresent() throws Exception {
        UUID runId = UUID.randomUUID();
        LabRunView view = LabRunView.inProgress(
                runId, ScenarioId.TIMEOUT_AFTER_COMMIT, ScenarioStatus.RUNNING, Instant.now(), List.of()
        );
        when(coordinator.getActiveRun()).thenReturn(Optional.of(view));

        mockMvc.perform(get("/api/lab/runs/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.scenarioId").value("TIMEOUT_AFTER_COMMIT"));
    }

    @Test
    @DisplayName("GET /api/lab/runs/active returns 204 No Content when no run active")
    void testGetActiveRunNone() throws Exception {
        when(coordinator.getActiveRun()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/lab/runs/active"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /api/lab/runs/{runId} returns 200 when run found")
    void testGetRunFound() throws Exception {
        UUID runId = UUID.randomUUID();
        LabRunView view = LabRunView.inProgress(
                runId, ScenarioId.WEBHOOK_RACE, ScenarioStatus.PASSED, Instant.now(), List.of()
        );
        when(coordinator.getRun(runId)).thenReturn(Optional.of(view));

        mockMvc.perform(get("/api/lab/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()));
    }

    @Test
    @DisplayName("GET /api/lab/runs/{runId} returns 404 Not Found when unknown runId")
    void testGetRunNotFound() throws Exception {
        UUID runId = UUID.randomUUID();
        when(coordinator.getRun(runId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/lab/runs/" + runId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/lab/runs returns history list")
    void testGetHistory() throws Exception {
        UUID runId = UUID.randomUUID();
        LabRunView view = LabRunView.inProgress(
                runId, ScenarioId.CORRUPTED_SNAPSHOT, ScenarioStatus.PASSED, Instant.now(), List.of()
        );
        when(coordinator.getHistory(anyInt())).thenReturn(List.of(view));

        mockMvc.perform(get("/api/lab/runs?limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scenarioId").value("CORRUPTED_SNAPSHOT"));
    }

    @Test
    @DisplayName("GET /api/lab/runs?limit=0 returns 400 Bad Request")
    void testGetHistoryInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/lab/runs?limit=0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/lab/runs with invalid body returns 400 Bad Request")
    void testStartRunInvalidBody() throws Exception {
        mockMvc.perform(post("/api/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\": null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/lab/environment includes CORS header for allowed origin")
    void testCorsAllowedOrigin() throws Exception {
        mockMvc.perform(get("/api/lab/environment")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("GET /api/lab/environment returns 403 Forbidden for unauthorized origin")
    void testCorsDisallowedOrigin() throws Exception {
        mockMvc.perform(get("/api/lab/environment")
                        .header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .doesNotExist("Access-Control-Allow-Origin"));
    }
}
