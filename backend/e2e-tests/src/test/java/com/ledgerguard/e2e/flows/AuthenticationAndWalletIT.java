package com.ledgerguard.e2e.flows;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerguard.e2e.AbstractE2ETest;
import com.ledgerguard.e2e.infrastructure.E2EHttpClient.HttpResponseView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthenticationAndWalletIT extends AbstractE2ETest {

    @Test
    @DisplayName("Customer registration, JWT authentication, and automatic ledger wallet initialization")
    void testCustomerRegistrationLoginAndWalletCreation() {
        String email = idFactory.createUniqueEmail("auth-customer");
        String password = idFactory.createStrongPassword();

        // 1. Register customer
        HttpResponseView registerResponse = httpClient.register(email, password, "CUSTOMER");
        assertThat(registerResponse.statusCode()).isEqualTo(201);
        JsonNode regJson = registerResponse.json();
        assertThat(regJson.get("email").asText()).isEqualTo(email);
        assertThat(regJson.get("role").asText()).isEqualTo("CUSTOMER");
        UUID userId = UUID.fromString(regJson.get("id").asText());

        // 2. Login to obtain JWT
        HttpResponseView loginResponse = httpClient.login(email, password);
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        JsonNode loginJson = loginResponse.json();
        assertThat(loginJson.has("accessToken")).isTrue();
        assertThat(loginJson.get("tokenType").asText()).isEqualTo("Bearer");

        // 3. Query initial wallet
        HttpResponseView walletResponse = httpClient.getApi("/api/wallets/me");
        assertThat(walletResponse.statusCode()).isEqualTo(200);
        JsonNode walletJson = walletResponse.json();
        assertThat(walletJson.get("currency").asText()).isEqualTo("INR");
        assertThat(walletJson.get("balanceMinor").asText()).isEqualTo("0");
        assertThat(walletJson.get("activeHoldAmountMinor").asText()).isEqualTo("0");
        assertThat(walletJson.get("availableBalanceMinor").asText()).isEqualTo("0");
        assertThat(walletJson.get("status").asText()).isEqualTo("ACTIVE");

        // 4. Verify database state
        UUID ledgerAccountId = UUID.fromString(walletJson.get("ledgerAccountId").asText());
        assertThat(dbProbe.accountExists(ledgerAccountId)).isTrue();
        assertThat(dbProbe.getSnapshotBalance(ledgerAccountId)).isEqualTo(0L);

        // 5. Invariant: double-entry balanced
        dbProbe.assertDoubleEntrySystemBalanced();
    }

    @Test
    @DisplayName("Duplicate registration is rejected with 400 Bad Request")
    void testDuplicateRegistrationFails() {
        String email = idFactory.createUniqueEmail("dup-user");
        String password = idFactory.createStrongPassword();

        HttpResponseView first = httpClient.register(email, password, "CUSTOMER");
        assertThat(first.statusCode()).isEqualTo(201);

        HttpResponseView second = httpClient.register(email, password, "CUSTOMER");
        assertThat(second.statusCode()).isEqualTo(400);
    }
}
