package com.ledgerguard.provider.api;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProviderWebhookAuthenticationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(springSecurity())
                .build();
    }

    private String sign(long timestamp, byte[] rawBody, String secret) {
        try {
            byte[] timestampBytes = Long.toString(timestamp).getBytes(StandardCharsets.UTF_8);
            byte[] dotBytes = ".".getBytes(StandardCharsets.UTF_8);
            byte[] canonicalBytes = new byte[timestampBytes.length + dotBytes.length + rawBody.length];

            System.arraycopy(timestampBytes, 0, canonicalBytes, 0, timestampBytes.length);
            System.arraycopy(dotBytes, 0, canonicalBytes, timestampBytes.length, dotBytes.length);
            System.arraycopy(rawBody, 0, canonicalBytes, timestampBytes.length + dotBytes.length, rawBody.length);

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(canonicalBytes);

            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return "sha256=" + sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String validWebhookJson(UUID eventId, UUID providerOpId, UUID clientOpId) {
        return """
                {
                    "eventId": "%s",
                    "eventSequence": 1,
                    "eventType": "PROVIDER_OPERATION_PROCESSING",
                    "providerOperationId": "%s",
                    "clientOperationId": "%s",
                    "operationType": "CREDIT",
                    "status": "PROCESSING",
                    "amountMinor": "5000",
                    "currency": "INR",
                    "occurredAt": "%s"
                }
                """.formatted(eventId, providerOpId, clientOpId, Instant.now().toString());
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.ledgerguard.funding.infrastructure.FundingOperationRepository fundingOperationRepository;

    @Test
    @DisplayName("Webhook request with valid signature and timestamp returns 200 OK")
    void validWebhookReturns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID accId = UUID.randomUUID();
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                userId, "auth-cust-" + userId + "@example.com", now, now
        );
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                accId, userId, now, now
        );

        UUID fundingId = UUID.randomUUID();
        com.ledgerguard.funding.domain.FundingOperation funding = new com.ledgerguard.funding.domain.FundingOperation(
                fundingId, userId, accId, 5000L, "INR", Instant.now()
        );
        fundingOperationRepository.saveAndFlush(funding);

        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        byte[] body = validWebhookJson(eventId, providerOpId, fundingId).getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String signature = sign(timestamp, body, RUNTIME_WEBHOOK_SECRET);

        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", signature)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    @DisplayName("Webhook request missing timestamp header returns 401 Unauthorized")
    void missingTimestampHeaderReturns401() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String responseBody = mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Signature", "sha256=" + "0".repeat(64))
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(RUNTIME_WEBHOOK_SECRET);
    }

    @Test
    @DisplayName("Webhook request missing signature header returns 401 Unauthorized")
    void missingSignatureHeaderReturns401() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String responseBody = mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(RUNTIME_WEBHOOK_SECRET);
    }

    @Test
    @DisplayName("Webhook request with expired timestamp returns 401 Unauthorized")
    void expiredTimestampReturns401() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long oldTimestamp = Instant.now().minusSeconds(350).getEpochSecond();
        String signature = sign(oldTimestamp, body, RUNTIME_WEBHOOK_SECRET);

        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(oldTimestamp))
                        .header("X-PSP-Webhook-Signature", signature)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Webhook request with far-future timestamp returns 401 Unauthorized")
    void futureTimestampReturns401() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long futureTimestamp = Instant.now().plusSeconds(350).getEpochSecond();
        String signature = sign(futureTimestamp, body, RUNTIME_WEBHOOK_SECRET);

        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(futureTimestamp))
                        .header("X-PSP-Webhook-Signature", signature)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Webhook request with invalid timestamp format returns 401 Unauthorized")
    void invalidTimestampFormatReturns401() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", "not-a-number")
                        .header("X-PSP-Webhook-Signature", "sha256=" + "a".repeat(64))
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Webhook request with invalid signature format (uppercase hex) returns 401 Unauthorized")
    void uppercaseHexSignatureReturns401() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String signature = sign(timestamp, body, RUNTIME_WEBHOOK_SECRET).toUpperCase(); // e.g. SHA256=ABCD...

        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", signature)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Webhook request with wrong secret returns 401 Unauthorized")
    void wrongSecretReturns401() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String wrongSecret = "totally_wrong_secret_that_is_at_least_32_bytes_long";
        String signature = sign(timestamp, body, wrongSecret);

        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", signature)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Webhook request with tampered payload bytes returns 401 Unauthorized")
    void tamperedPayloadReturns401() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        byte[] originalBody = validWebhookJson(eventId, providerOpId, clientOpId).getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String signature = sign(timestamp, originalBody, RUNTIME_WEBHOOK_SECRET);

        byte[] tamperedBody = (new String(originalBody, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", signature)
                        .content(tamperedBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Extreme timestamp values (Long.MAX_VALUE, Long.MIN_VALUE) are safely rejected with 401 without overflow exception")
    void extremeTimestampValuesSafelyRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        // Long.MAX_VALUE
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(Long.MAX_VALUE))
                        .header("X-PSP-Webhook-Signature", "sha256=" + "a".repeat(64))
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Long.MIN_VALUE
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(Long.MIN_VALUE))
                        .header("X-PSP-Webhook-Signature", "sha256=" + "a".repeat(64))
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Malformed signature formats (missing prefix, wrong length, non-hex) return 401 Unauthorized")
    void malformedSignatureFormatsRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();

        // Missing "sha256=" prefix
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", "a".repeat(64))
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Wrong length (63 hex chars)
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", "sha256=" + "a".repeat(63))
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Wrong length (65 hex chars)
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", "sha256=" + "a".repeat(65))
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Non-hex characters
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", "sha256=" + "z".repeat(64))
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Raw-body sensitivity: semantically equivalent JSON with different raw bytes fails signature")
    void rawBodySensitivitySemanticallyEquivalentJsonFails() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        String compactJson = "{\"eventId\":\"" + eventId + "\",\"eventSequence\":1,\"eventType\":\"PROVIDER_OPERATION_SUCCEEDED\",\"providerOperationId\":\"" + providerOpId + "\",\"clientOperationId\":\"" + clientOpId + "\",\"operationType\":\"CREDIT\",\"status\":\"SUCCEEDED\",\"amountMinor\":\"5000\",\"currency\":\"INR\",\"occurredAt\":\"" + Instant.now().toString() + "\"}";
        byte[] compactBytes = compactJson.getBytes(StandardCharsets.UTF_8);

        long timestamp = Instant.now().getEpochSecond();
        String signatureOfCompact = sign(timestamp, compactBytes, RUNTIME_WEBHOOK_SECRET);

        // Pretty/spaced JSON represents identical semantic data but different canonical bytes
        String spacedJson = "{\n  \"eventId\": \"" + eventId + "\",\n  \"eventSequence\": 1,\n  \"eventType\": \"PROVIDER_OPERATION_SUCCEEDED\",\n  \"providerOperationId\": \"" + providerOpId + "\",\n  \"clientOperationId\": \"" + clientOpId + "\",\n  \"operationType\": \"CREDIT\",\n  \"status\": \"SUCCEEDED\",\n  \"amountMinor\": \"5000\",\n  \"currency\": \"INR\",\n  \"occurredAt\": \"" + Instant.now().toString() + "\"\n}";
        byte[] spacedBytes = spacedJson.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                        .header("X-PSP-Webhook-Signature", signatureOfCompact)
                        .content(spacedBytes))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Timestamp boundary validation: inside boundary (290s) accepted, outside boundary (305s) rejected")
    void timestampBoundaryValidation() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        // Inside boundary: 290s in past -> Not rejected by HMAC (proceeds to JSON/400 or ACCEPTED/202, not 401)
        long tsPast290 = Instant.now().minusSeconds(290).getEpochSecond();
        String sig1 = sign(tsPast290, body, RUNTIME_WEBHOOK_SECRET);
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(tsPast290))
                        .header("X-PSP-Webhook-Signature", sig1)
                        .content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));

        // Inside boundary: 290s in future -> Not rejected by HMAC (not 401)
        long tsFuture290 = Instant.now().plusSeconds(290).getEpochSecond();
        String sig2 = sign(tsFuture290, body, RUNTIME_WEBHOOK_SECRET);
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(tsFuture290))
                        .header("X-PSP-Webhook-Signature", sig2)
                        .content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));

        // Outside boundary: 305s in past -> Rejected (401)
        long tsPast305 = Instant.now().minusSeconds(305).getEpochSecond();
        String sig3 = sign(tsPast305, body, RUNTIME_WEBHOOK_SECRET);
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(tsPast305))
                        .header("X-PSP-Webhook-Signature", sig3)
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Outside boundary: 305s in future -> Rejected (401)
        long tsFuture305 = Instant.now().plusSeconds(305).getEpochSecond();
        String sig4 = sign(tsFuture305, body, RUNTIME_WEBHOOK_SECRET);
        mockMvc.perform(post("/api/provider/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PSP-Webhook-Timestamp", String.valueOf(tsFuture305))
                        .header("X-PSP-Webhook-Signature", sig4)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}
