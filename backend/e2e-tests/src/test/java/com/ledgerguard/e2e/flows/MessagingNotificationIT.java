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

public class MessagingNotificationIT extends AbstractE2ETest {

    @Test
    @DisplayName("End-to-end messaging: Transaction outbox -> Kafka publication -> Notification Worker consumption -> notification_deliveries persistence")
    void testOutboxKafkaNotificationWorkerPipeline() {
        // 1. Register Customer A
        String emailA = idFactory.createUniqueEmail("notify-sender");
        String passwordA = idFactory.createStrongPassword();
        HttpResponseView regA = httpClient.register(emailA, passwordA, "CUSTOMER");
        assertThat(regA.statusCode()).isEqualTo(201);

        // Login Customer A
        httpClient.login(emailA, passwordA);

        // 2. Register Customer B
        String emailB = idFactory.createUniqueEmail("notify-recipient");
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

        // 4. Customer A executes transfer to Customer B
        UUID transferKey = idFactory.createIdempotencyKey();
        Map<String, Object> transferPayload = Map.of(
                "destinationLedgerAccountId", customerBAccountId.toString(),
                "amountMinor", 20000L
        );

        HttpResponseView transferRes = httpClient.postApi("/api/transfers", transferPayload, transferKey);
        assertThat(transferRes.statusCode()).isEqualTo(201);
        JsonNode transferJson = transferRes.json();
        UUID transferId = UUID.fromString(transferJson.get("transferId").asText());

        // 5. Await notification-worker consumption and notification_deliveries persistence
        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    int deliveryCount = dbProbe.countNotificationDeliveriesByAggregateId(transferId);
                    assertThat(deliveryCount).as("Expected notification delivery for transfer %s", transferId).isGreaterThanOrEqualTo(1);
                });

        // 6. Verify ledger integrity
        dbProbe.assertDoubleEntrySystemBalanced();
        dbProbe.assertSnapshotParity();
    }
}
