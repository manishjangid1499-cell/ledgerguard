package com.ledgerguard.e2e.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.e2e.AbstractE2ETest;
import com.ledgerguard.e2e.infrastructure.E2EHttpClient.HttpResponseView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class ExternalWalletFundingIT extends AbstractE2ETest {

    @Test
    @DisplayName("Execute external customer wallet funding with idempotency replay, conflict rejection, and snapshot parity")
    void testExternalWalletFundingFlow() {
        // 1. Register Customer
        String email = idFactory.createUniqueEmail("funding-customer");
        String password = idFactory.createStrongPassword();
        HttpResponseView regRes = httpClient.register(email, password, "CUSTOMER");
        assertThat(regRes.statusCode()).isEqualTo(201);

        // Login
        httpClient.login(email, password);

        // Verify initial balance is 0
        HttpResponseView initialWallet = httpClient.getApi("/api/wallets/me");
        assertThat(initialWallet.statusCode()).isEqualTo(200);
        assertThat(initialWallet.json().get("balanceMinor").asText()).isEqualTo("0");

        // 2. Fund wallet with 75,000 minor units (750.00 INR)
        UUID idempotencyKey = idFactory.createIdempotencyKey();
        Map<String, Object> fundingPayload = Map.of("amountMinor", "75000");

        HttpResponseView fundingRes = httpClient.postApi("/api/funding", fundingPayload, idempotencyKey);
        assertThat(fundingRes.statusCode()).isIn(201, 202);
        JsonNode fundingJson = fundingRes.json();
        UUID fundingId = UUID.fromString(fundingJson.get("fundingId").asText());
        assertThat(fundingJson.get("status").asText()).isIn("SUCCEEDED", "PROCESSING");
        assertThat(fundingJson.get("replayed").asBoolean()).isFalse();

        // 3. Replay with identical idempotency key -> 200 OK or 202 ACCEPTED with replayed=true
        HttpResponseView replayRes = httpClient.postApi("/api/funding", fundingPayload, idempotencyKey);
        assertThat(replayRes.statusCode()).isIn(200, 202);
        assertThat(replayRes.json().get("replayed").asBoolean()).isTrue();
        assertThat(replayRes.json().get("fundingId").asText()).isEqualTo(fundingId.toString());

        // 4. Conflicting replay with modified amount -> 409 Conflict
        Map<String, Object> conflictingPayload = Map.of("amountMinor", "85000");
        HttpResponseView conflictRes = httpClient.postApi("/api/funding", conflictingPayload, idempotencyKey);
        assertThat(conflictRes.statusCode()).isEqualTo(409);

        // 5. Verify updated wallet balance reaches 75000
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            HttpResponseView updatedWallet = httpClient.getApi("/api/wallets/me");
            assertThat(updatedWallet.statusCode()).isEqualTo(200);
            assertThat(updatedWallet.json().get("balanceMinor").asText()).isEqualTo("75000");
            assertThat(updatedWallet.json().get("availableBalanceMinor").asText()).isEqualTo("75000");
        });

        // 6. Verify ledger invariants
        dbProbe.assertDoubleEntrySystemBalanced();
        dbProbe.assertSnapshotParity();
    }
}
