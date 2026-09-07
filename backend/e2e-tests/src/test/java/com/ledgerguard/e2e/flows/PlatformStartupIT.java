package com.ledgerguard.e2e.flows;

import com.ledgerguard.e2e.AbstractE2ETest;
import com.ledgerguard.e2e.infrastructure.E2EHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E Journey 1: Platform Startup & Flyway Schema Migrations")
public class PlatformStartupIT extends AbstractE2ETest {

    @Test
    @DisplayName("API Actuator Health reports UP")
    void testApiHealthUp() {
        E2EHttpClient.HttpResponseView res = http.getApi("/actuator/health", false);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.json().get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("Flyway migrations V1 through V17 are applied on ledgerguard; V18 is ABSENT")
    void testLedgerGuardFlywayMigrations() {
        List<String> versions = db.getAppliedFlywayVersions("ledgerguard");
        assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17");
        assertThat(versions).doesNotContain("18");
    }

    @Test
    @DisplayName("Flyway migrations are applied on psp_simulator and notification_worker")
    void testAuxiliaryFlywayMigrations() {
        List<String> pspVersions = db.getAppliedFlywayVersions("psp_simulator");
        assertThat(pspVersions).contains("1");

        List<String> workerVersions = db.getAppliedFlywayVersions("notification_worker");
        assertThat(workerVersions).contains("1");
    }

    @Test
    @DisplayName("Verify PSP simulator real HTTP provider boundary is reachable and responsive")
    void testPspDirectCall() {
        java.util.Map<String, Object> payload = java.util.Map.of(
                "clientOperationId", java.util.UUID.randomUUID().toString(),
                "operationType", "CREDIT",
                "amountMinor", "5000",
                "currency", "INR"
        );
        E2EHttpClient.HttpResponseView res = http.postPsp("/api/provider/operations", payload);
        assertThat(res.statusCode()).isIn(200, 201);
        assertThat(res.json().get("replayed").asBoolean()).isFalse();
    }
}
