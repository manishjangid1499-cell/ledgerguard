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

public class MerchantPaymentAndRefundIT extends AbstractE2ETest {

    @Test
    @DisplayName("Merchant payment authorization, capture, and subsequent full refund with balance restoration")
    void testMerchantPaymentAndFullRefund() {
        // 1. Register Merchant
        String merchantEmail = idFactory.createUniqueEmail("merchant");
        String merchantPassword = idFactory.createStrongPassword();
        HttpResponseView regMerchant = httpClient.register(merchantEmail, merchantPassword, "MERCHANT");
        assertThat(regMerchant.statusCode()).isEqualTo(201);

        // Login Merchant to get merchant ledgerAccountId
        httpClient.login(merchantEmail, merchantPassword);
        HttpResponseView merchantWallet = httpClient.getApi("/api/wallets/me");
        assertThat(merchantWallet.statusCode()).isEqualTo(200);
        UUID merchantAccountId = UUID.fromString(merchantWallet.json().get("ledgerAccountId").asText());

        // 2. Register Customer
        String customerEmail = idFactory.createUniqueEmail("shopper");
        String customerPassword = idFactory.createStrongPassword();
        HttpResponseView regCustomer = httpClient.register(customerEmail, customerPassword, "CUSTOMER");
        assertThat(regCustomer.statusCode()).isEqualTo(201);

        // Login Customer and fund wallet with 50,000 minor units (500.00 INR)
        httpClient.login(customerEmail, customerPassword);
        UUID fundingKey = idFactory.createIdempotencyKey();
        HttpResponseView fundingRes = httpClient.postApi("/api/funding", Map.of("amountMinor", "50000"), fundingKey);
        assertThat(fundingRes.statusCode()).isIn(201, 202);

        // Await Customer balance to reach 50000
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            HttpResponseView res = httpClient.getApi("/api/wallets/me");
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.json().get("balanceMinor").asText()).isEqualTo("50000");
        });

        // 3. Customer executes merchant payment of 20,000 minor units (200.00 INR)
        UUID paymentKey = idFactory.createIdempotencyKey();
        Map<String, Object> paymentPayload = Map.of(
                "merchantLedgerAccountId", merchantAccountId.toString(),
                "amountMinor", 20000L
        );
        HttpResponseView paymentRes = httpClient.postApi("/api/payments", paymentPayload, paymentKey);
        assertThat(paymentRes.statusCode()).isEqualTo(201);
        JsonNode paymentJson = paymentRes.json();
        UUID paymentId = UUID.fromString(paymentJson.get("paymentId").asText());
        assertThat(paymentJson.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(paymentJson.get("replayed").asBoolean()).isFalse();

        // 4. Verify post-payment balances: Customer = 30,000; Merchant = 19,800 (1% fee deducted)
        HttpResponseView walletAfterPay = httpClient.getApi("/api/wallets/me");
        assertThat(walletAfterPay.statusCode()).isEqualTo(200);
        assertThat(walletAfterPay.json().get("balanceMinor").asText()).isEqualTo("30000");

        httpClient.login(merchantEmail, merchantPassword);
        HttpResponseView merchantAfterPay = httpClient.getApi("/api/wallets/me");
        assertThat(merchantAfterPay.statusCode()).isEqualTo(200);
        assertThat(merchantAfterPay.json().get("balanceMinor").asText()).isEqualTo("19800");

        // 5. Merchant issues full refund of 20,000 minor units
        UUID refundKey = idFactory.createIdempotencyKey();
        Map<String, Object> refundPayload = Map.of(
                "amountMinor", 20000L
        );
        HttpResponseView refundRes = httpClient.postApi("/api/payments/" + paymentId + "/refund", refundPayload, refundKey);
        assertThat(refundRes.statusCode()).isEqualTo(201);
        JsonNode refundJson = refundRes.json();
        assertThat(refundJson.has("refundId")).isTrue();
        assertThat(refundJson.get("replayed").asBoolean()).isFalse();

        // 6. Verify restored balances: Customer = 50,000; Merchant = 0
        HttpResponseView merchantAfterRefund = httpClient.getApi("/api/wallets/me");
        assertThat(merchantAfterRefund.statusCode()).isEqualTo(200);
        assertThat(merchantAfterRefund.json().get("balanceMinor").asText()).isEqualTo("0");

        httpClient.login(customerEmail, customerPassword);
        HttpResponseView customerAfterRefund = httpClient.getApi("/api/wallets/me");
        assertThat(customerAfterRefund.statusCode()).isEqualTo(200);
        assertThat(customerAfterRefund.json().get("balanceMinor").asText()).isEqualTo("50000");

        // 7. Verify ledger invariants
        dbProbe.assertDoubleEntrySystemBalanced();
        dbProbe.assertSnapshotParity();
    }
}
