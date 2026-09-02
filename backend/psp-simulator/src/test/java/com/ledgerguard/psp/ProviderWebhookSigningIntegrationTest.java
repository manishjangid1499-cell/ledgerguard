package com.ledgerguard.psp;

import com.ledgerguard.psp.api.CreateOperationRequest;
import com.ledgerguard.psp.api.OperationResponse;
import com.ledgerguard.psp.api.ScenarioRequest;
import com.ledgerguard.psp.domain.SimulatorScenario;
import com.ledgerguard.psp.infrastructure.ProviderOperationRepository;
import com.ledgerguard.psp.infrastructure.ProviderWebhookRepository;
import com.ledgerguard.psp.infrastructure.ScenarioRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderWebhookSigningIntegrationTest extends AbstractPspSimulatorIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ProviderOperationRepository operationRepository;

    @Autowired
    private ProviderWebhookRepository webhookRepository;

    @Autowired
    private ScenarioRegistry scenarioRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    private static HttpServer mockWebhookServer;
    private static int webhookPort;

    record CapturedWebhook(byte[] body, String timestamp, String signature) {}
    private static final List<CapturedWebhook> capturedWebhooks = new CopyOnWriteArrayList<>();

    private RestClient pspClient;

    @BeforeAll
    static void startWebhookServer() throws IOException {
        mockWebhookServer = HttpServer.create(new InetSocketAddress(0), 0);
        webhookPort = mockWebhookServer.getAddress().getPort();
        mockWebhookServer.createContext("/webhook", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String ts = exchange.getRequestHeaders().getFirst("X-PSP-Webhook-Timestamp");
                    String sig = exchange.getRequestHeaders().getFirst("X-PSP-Webhook-Signature");
                    try (InputStream is = exchange.getRequestBody()) {
                        byte[] body = is.readAllBytes();
                        capturedWebhooks.add(new CapturedWebhook(body, ts, sig));
                    }
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
                exchange.close();
            }
        });
        mockWebhookServer.setExecutor(Executors.newCachedThreadPool());
        mockWebhookServer.start();
    }

    @AfterAll
    static void stopWebhookServer() {
        if (mockWebhookServer != null) {
            mockWebhookServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        capturedWebhooks.clear();
        scenarioRegistry.clearAll();
        webhookRepository.deleteAll();
        operationRepository.deleteAll();

        this.pspClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private String webhookUrl() {
        return "http://localhost:" + webhookPort + "/webhook";
    }

    private boolean verifyHmac(String timestampHeader, String signatureHeader, byte[] rawBody, String secret) {
        try {
            if (!Pattern.compile("^sha256=[0-9a-f]{64}$").matcher(signatureHeader).matches()) {
                return false;
            }
            byte[] timestampBytes = timestampHeader.getBytes(StandardCharsets.UTF_8);
            byte[] dotBytes = ".".getBytes(StandardCharsets.UTF_8);
            byte[] canonicalBytes = new byte[timestampBytes.length + dotBytes.length + rawBody.length];

            System.arraycopy(timestampBytes, 0, canonicalBytes, 0, timestampBytes.length);
            System.arraycopy(dotBytes, 0, canonicalBytes, timestampBytes.length, dotBytes.length);
            System.arraycopy(rawBody, 0, canonicalBytes, timestampBytes.length + dotBytes.length, rawBody.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computedDigest = mac.doFinal(canonicalBytes);

            StringBuilder sb = new StringBuilder(64);
            for (byte b : computedDigest) {
                sb.append(String.format("%02x", b));
            }
            String computedSig = "sha256=" + sb.toString();

            return MessageDigest.isEqual(
                    computedSig.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("Outbound webhook has eventSequence=1 and valid HMAC-SHA256 signature signed with runtime secret")
    void outboundWebhookSigningAndSequenceValidation() throws Exception {
        UUID clientOpId = UUID.randomUUID();
        CreateOperationRequest request = new CreateOperationRequest(
                clientOpId, "CREDIT", "5000", "INR", webhookUrl()
        );

        ResponseEntity<OperationResponse> response = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(OperationResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);

        // Wait for webhook delivery
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(capturedWebhooks).hasSize(1));

        CapturedWebhook captured = capturedWebhooks.get(0);
        assertThat(captured.timestamp()).isNotNull();
        assertThat(captured.signature()).isNotNull();

        // 1. Verify JSON contains eventSequence = 1
        JsonNode json = objectMapper.readTree(captured.body());
        assertThat(json.has("eventSequence")).isTrue();
        assertThat(json.get("eventSequence").asLong()).isEqualTo(1L);

        // 2. Verify HMAC-SHA256 signature matches canonical bytes
        boolean valid = verifyHmac(captured.timestamp(), captured.signature(), captured.body(), RUNTIME_WEBHOOK_SECRET);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("DUPLICATE_WEBHOOK scenario sends identical raw body payloads with valid signatures")
    void duplicateWebhookDeliveryPayloadIdentityAndSignature() throws Exception {
        UUID clientOpId = UUID.randomUUID();
        scenarioRegistry.register(clientOpId, new com.ledgerguard.psp.domain.ScenarioConfig(SimulatorScenario.DUPLICATE_WEBHOOK, 0, 0));

        CreateOperationRequest request = new CreateOperationRequest(
                clientOpId, "CREDIT", "7500", "INR", webhookUrl()
        );

        pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(OperationResponse.class);

        // Wait for both deliveries
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(capturedWebhooks).hasSize(2));

        CapturedWebhook delivery1 = capturedWebhooks.get(0);
        CapturedWebhook delivery2 = capturedWebhooks.get(1);

        // Authoritative payload bytes must be identical
        assertThat(delivery1.body()).isEqualTo(delivery2.body());

        // Both must be validly signed
        assertThat(verifyHmac(delivery1.timestamp(), delivery1.signature(), delivery1.body(), RUNTIME_WEBHOOK_SECRET)).isTrue();
        assertThat(verifyHmac(delivery2.timestamp(), delivery2.signature(), delivery2.body(), RUNTIME_WEBHOOK_SECRET)).isTrue();
    }
}
