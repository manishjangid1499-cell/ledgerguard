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

public class InternalTransferIT extends AbstractE2ETest {

    @Test
    @DisplayName("Execute authenticated internal transfer with balance conservation and double-entry balance")
    void testInternalTransferBetweenWallets() {
        // 1. Register Customer A
        String emailA = idFactory.createUniqueEmail("sender-a");
        String passwordA = idFactory.createStrongPassword();
        HttpResponseView regA = httpClient.register(emailA, passwordA, "CUSTOMER");
        assertThat(regA.statusCode()).isEqualTo(201);

        // Login Customer A
        httpClient.login(emailA, passwordA);
        HttpResponseView walletARes = httpClient.getApi("/api/wallets/me");
        assertThat(walletARes.statusCode()).isEqualTo(200);
        UUID customerAAccountId = UUID.fromString(walletARes.json().get("ledgerAccountId").asText());

        // 2. Register Customer B
        String emailB = idFactory.createUniqueEmail("receiver-b");
        String passwordB = idFactory.createStrongPassword();
        HttpResponseView regB = httpClient.register(emailB, passwordB, "CUSTOMER");
        assertThat(regB.statusCode()).isEqualTo(201);

        // Login Customer B to get ledgerAccountId
        httpClient.login(emailB, passwordB);
        HttpResponseView walletBRes = httpClient.getApi("/api/wallets/me");
        assertThat(walletBRes.statusCode()).isEqualTo(200);
        UUID customerBAccountId = UUID.fromString(walletBRes.json().get("ledgerAccountId").asText());

        // 3. Fund Customer A with 100,000 minor units (1,000.00 INR)
        httpClient.login(emailA, passwordA);
        UUID fundingKey = idFactory.createIdempotencyKey();
        HttpResponseView fundingRes = httpClient.postApi("/api/funding", Map.of("amountMinor", "100000"), fundingKey);
        assertThat(fundingRes.statusCode()).isIn(201, 202);

        // Await Customer A balance to reach 100000
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            HttpResponseView res = httpClient.getApi("/api/wallets/me");
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.json().get("balanceMinor").asText()).isEqualTo("100000");
        });

        // 4. Customer A executes transfer of 30,000 minor units (300.00 INR) to Customer B
        UUID transferKey = idFactory.createIdempotencyKey();
        Map<String, Object> transferPayload = Map.of(
                "destinationLedgerAccountId", customerBAccountId.toString(),
                "amountMinor", 30000L
        );

        HttpResponseView transferRes = httpClient.postApi("/api/transfers", transferPayload, transferKey);
        assertThat(transferRes.statusCode()).isEqualTo(201);
        JsonNode transferJson = transferRes.json();
        UUID transferId = UUID.fromString(transferJson.get("transferId").asText());
        assertThat(transferJson.has("journalTransactionId")).isTrue();
        assertThat(transferJson.get("replayed").asBoolean()).isFalse();

        // 5. Replay transfer with identical idempotency key -> returns existing record with replayed=true
        HttpResponseView replayRes = httpClient.postApi("/api/transfers", transferPayload, transferKey);
        assertThat(replayRes.statusCode()).isEqualTo(200);
        assertThat(replayRes.json().get("replayed").asBoolean()).isTrue();
        assertThat(replayRes.json().get("transferId").asText()).isEqualTo(transferId.toString());

        // 6. Conflicting replay with different amount -> 409 Conflict
        Map<String, Object> conflictPayload = Map.of(
                "destinationLedgerAccountId", customerBAccountId.toString(),
                "amountMinor", 40000L
        );
        HttpResponseView conflictRes = httpClient.postApi("/api/transfers", conflictPayload, transferKey);
        assertThat(conflictRes.statusCode()).isEqualTo(409);

        // 7. Verify balances: Customer A = 70,000; Customer B = 30,000
        HttpResponseView finalWalletA = httpClient.getApi("/api/wallets/me");
        assertThat(finalWalletA.statusCode()).isEqualTo(200);
        assertThat(finalWalletA.json().get("balanceMinor").asText()).isEqualTo("70000");

        httpClient.login(emailB, passwordB);
        HttpResponseView finalWalletB = httpClient.getApi("/api/wallets/me");
        assertThat(finalWalletB.statusCode()).isEqualTo(200);
        assertThat(finalWalletB.json().get("balanceMinor").asText()).isEqualTo("30000");

        // 8. Invariants: Conservation of funds (dA + dB = 0) and snapshot parity
        dbProbe.assertZeroSumTransfer(customerAAccountId, customerBAccountId, 100000L, 30000L);
        dbProbe.assertDoubleEntrySystemBalanced();
        dbProbe.assertSnapshotParity();
    }
}
