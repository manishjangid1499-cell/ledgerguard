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

public class ExternalPayoutAndHoldIT extends AbstractE2ETest {

    @Test
    @DisplayName("Execute external payout with balance hold reservation, settlement, and insufficient fund guard")
    void testExternalPayoutAndHoldFlow() {
        // 1. Register Customer
        String email = idFactory.createUniqueEmail("payout-customer");
        String password = idFactory.createStrongPassword();
        HttpResponseView regRes = httpClient.register(email, password, "CUSTOMER");
        assertThat(regRes.statusCode()).isEqualTo(201);

        // Login
        httpClient.login(email, password);

        // 2. Fund Customer with 100,000 minor units (1,000.00 INR)
        UUID fundingKey = idFactory.createIdempotencyKey();
        HttpResponseView fundingRes = httpClient.postApi("/api/funding", Map.of("amountMinor", "100000"), fundingKey);
        assertThat(fundingRes.statusCode()).isIn(201, 202);

        // Await customer balance to reach 100000
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            HttpResponseView res = httpClient.getApi("/api/wallets/me");
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.json().get("balanceMinor").asText()).isEqualTo("100000");
        });

        // 3. Attempt payout with amount exceeding available balance (200,000 minor units) -> Bad Request / Unprocessable
        UUID excessKey = idFactory.createIdempotencyKey();
        HttpResponseView excessPayoutRes = httpClient.postApi("/api/payouts", Map.of("amountMinor", "200000"), excessKey);
        assertThat(excessPayoutRes.statusCode()).isGreaterThanOrEqualTo(400);

        // 4. Request valid payout of 40,000 minor units (400.00 INR)
        UUID payoutKey = idFactory.createIdempotencyKey();
        Map<String, Object> payoutPayload = Map.of("amountMinor", "40000");

        HttpResponseView payoutRes = httpClient.postApi("/api/payouts", payoutPayload, payoutKey);
        assertThat(payoutRes.statusCode()).isIn(201, 202);
        JsonNode payoutJson = payoutRes.json();
        UUID payoutId = UUID.fromString(payoutJson.get("payoutId").asText());
        assertThat(payoutJson.get("replayed").asBoolean()).isFalse();

        // 5. Replay with identical idempotency key -> 200 OK or 202 with replayed=true
        HttpResponseView replayRes = httpClient.postApi("/api/payouts", payoutPayload, payoutKey);
        assertThat(replayRes.statusCode()).isIn(200, 202);
        assertThat(replayRes.json().get("replayed").asBoolean()).isTrue();
        assertThat(replayRes.json().get("payoutId").asText()).isEqualTo(payoutId.toString());

        // 6. Verify wallet balances (balance=60,000, holds=0, available=60,000)
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            HttpResponseView finalWallet = httpClient.getApi("/api/wallets/me");
            assertThat(finalWallet.statusCode()).isEqualTo(200);
            assertThat(finalWallet.json().get("balanceMinor").asText()).isEqualTo("60000");
            assertThat(finalWallet.json().get("activeHoldAmountMinor").asText()).isEqualTo("0");
            assertThat(finalWallet.json().get("availableBalanceMinor").asText()).isEqualTo("60000");
        });

        // 7. Verify ledger invariants
        dbProbe.assertDoubleEntrySystemBalanced();
        dbProbe.assertSnapshotParity();
    }
}
