package com.ledgerguard.psp;

import com.ledgerguard.psp.api.CreateOperationRequest;
import com.ledgerguard.psp.api.OperationResponse;
import com.ledgerguard.psp.api.ScenarioRequest;
import com.ledgerguard.psp.api.ScenarioResponse;
import com.ledgerguard.psp.domain.OperationStatus;
import com.ledgerguard.psp.domain.ProviderWebhook;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PspSimulatorIntegrationTest extends AbstractPspSimulatorIntegrationTest {

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
    private static final List<String> receivedWebhookPayloads = new CopyOnWriteArrayList<>();

    private RestClient pspClient;

    @BeforeAll
    static void startWebhookServer() throws IOException {
        mockWebhookServer = HttpServer.create(new InetSocketAddress(0), 0);
        webhookPort = mockWebhookServer.getAddress().getPort();
        mockWebhookServer.createContext("/webhook", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    try (InputStream is = exchange.getRequestBody()) {
                        String body = new String(is.readAllBytes());
                        receivedWebhookPayloads.add(body);
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
        receivedWebhookPayloads.clear();
        scenarioRegistry.clearAll();
        webhookRepository.deleteAll();
        operationRepository.deleteAll();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.pspClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();
    }

    private String webhookUrl() {
        return "http://localhost:" + webhookPort + "/webhook";
    }

    private List<String> getReceivedPayloadsFor(UUID clientOperationId) {
        String match = clientOperationId.toString();
        return receivedWebhookPayloads.stream()
                .filter(p -> p.contains(match))
                .toList();
    }

    @Test
    @DisplayName("NORMAL_SUCCESS creates operation, returns 201, schedules and dispatches 1 webhook")
    void normalSuccessScenario() {
        UUID clientOpId = UUID.randomUUID();
        CreateOperationRequest request = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "10000",
                "INR",
                webhookUrl()
        );

        ResponseEntity<OperationResponse> response = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().clientOperationId()).isEqualTo(clientOpId);
        assertThat(response.getBody().status()).isEqualTo("SUCCEEDED");
        assertThat(response.getBody().amountMinor()).isEqualTo("10000");
        assertThat(response.getBody().currency()).isEqualTo("INR");

        // Verify DB
        assertThat(operationRepository.count()).isEqualTo(1);
        assertThat(webhookRepository.count()).isEqualTo(1);

        // Verify Webhook delivery via Awaitility
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(getReceivedPayloadsFor(clientOpId)).hasSize(1));

        String payload = getReceivedPayloadsFor(clientOpId).getFirst();
        assertThat(payload).contains(clientOpId.toString());
        assertThat(payload).contains("PROVIDER_OPERATION_SUCCEEDED");
        assertThat(payload).contains("10000");
    }

    @Test
    @DisplayName("Idempotent replay returns 200 OK with same operation and does not duplicate webhooks")
    void idempotentReplayReturnsSameOperation() {
        UUID clientOpId = UUID.randomUUID();
        CreateOperationRequest request = new CreateOperationRequest(
                clientOpId,
                "DEBIT",
                "50000",
                "INR",
                webhookUrl()
        );

        // First call -> 201 Created
        ResponseEntity<OperationResponse> response1 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second call (replay) -> 200 OK
        ResponseEntity<OperationResponse> response2 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response2.getBody().providerOperationId()).isEqualTo(response1.getBody().providerOperationId());

        assertThat(operationRepository.count()).isEqualTo(1);
        assertThat(webhookRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Conflicting replay with different amount returns 409 Conflict")
    void conflictingReplayReturns409() {
        UUID clientOpId = UUID.randomUUID();
        CreateOperationRequest request1 = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "10000",
                "INR",
                webhookUrl()
        );
        ResponseEntity<OperationResponse> response1 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request1)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Conflicting request (different amount)
        CreateOperationRequest request2 = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "20000",
                "INR",
                webhookUrl()
        );
        ResponseEntity<String> response2 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request2)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(String.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(operationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Conflicting replay with different webhookUrl returns 409 Conflict")
    void conflictingWebhookUrlReplayReturns409() {
        UUID clientOpId = UUID.randomUUID();
        String urlA = webhookUrl() + "/url-a";
        String urlB = webhookUrl() + "/url-b";

        CreateOperationRequest request1 = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "10000",
                "INR",
                urlA
        );
        ResponseEntity<OperationResponse> response1 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request1)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Conflicting request (different webhookUrl)
        CreateOperationRequest request2 = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "10000",
                "INR",
                urlB
        );
        ResponseEntity<String> response2 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request2)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(String.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(operationRepository.count()).isEqualTo(1);
        List<ProviderWebhook> storedWebhooks = webhookRepository.findAll();
        assertThat(storedWebhooks).hasSize(1);
        assertThat(storedWebhooks.get(0).getTargetUrl()).isEqualTo(urlA);
        assertThat(storedWebhooks.stream().noneMatch(w -> w.getTargetUrl().equals(urlB))).isTrue();
    }

    @Test
    @DisplayName("20 concurrent identical operations create exactly 1 provider operation and 1 webhook set")
    void concurrentIdempotentOperations() throws Exception {
        UUID clientOpId = UUID.randomUUID();
        CreateOperationRequest request = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "75000",
                "INR",
                webhookUrl()
        );

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<ResponseEntity<OperationResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return pspClient.post()
                        .uri("/api/provider/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .onStatus(status -> true, (req, resp) -> {})
                        .toEntity(OperationResponse.class);
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        Set<UUID> opIds = new HashSet<>();
        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger okCount = new AtomicInteger(0);

        for (Future<ResponseEntity<OperationResponse>> future : futures) {
            ResponseEntity<OperationResponse> resp = future.get();
            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            if (resp.getStatusCode() == HttpStatus.CREATED) {
                createdCount.incrementAndGet();
            } else if (resp.getStatusCode() == HttpStatus.OK) {
                okCount.incrementAndGet();
            }
            opIds.add(resp.getBody().providerOperationId());
        }

        assertThat(opIds).hasSize(1);
        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(okCount.get()).isEqualTo(19);
        assertThat(operationRepository.count()).isEqualTo(1);
        assertThat(webhookRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("TEMPORARY_500 returns 500 for configured failure count, then succeeds and cleans up")
    void temporary500Scenario() {
        UUID clientOpId = UUID.randomUUID();

        // Configure scenario: TEMPORARY_500 with 2 failures
        ScenarioRequest scenarioRequest = new ScenarioRequest(SimulatorScenario.TEMPORARY_500, 0L, 2);
        pspClient.put()
                .uri("/api/simulator/scenarios/" + clientOpId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(scenarioRequest)
                .retrieve()
                .toBodilessEntity();

        CreateOperationRequest opRequest = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "10000",
                "INR",
                webhookUrl()
        );

        // Attempt 1 -> 500
        ResponseEntity<String> resp1 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(opRequest)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(String.class);
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(operationRepository.count()).isEqualTo(0);

        // Attempt 2 -> 500
        ResponseEntity<String> resp2 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(opRequest)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(String.class);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(operationRepository.count()).isEqualTo(0);

        // Attempt 3 -> 201 Created
        ResponseEntity<OperationResponse> resp3 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(opRequest)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(resp3.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(operationRepository.count()).isEqualTo(1);
        assertThat(webhookRepository.count()).isEqualTo(1);

        // Attempt 4 (replay) -> 200 OK (scenario is consumed/cleaned)
        ResponseEntity<OperationResponse> resp4 = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(opRequest)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(resp4.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp4.getBody().providerOperationId()).isEqualTo(resp3.getBody().providerOperationId());
    }

    @Test
    @DisplayName("TIMEOUT_AFTER_SUCCESS commits operation before delay so status check finds SUCCEEDED even if client timed out")
    void timeoutAfterSuccessScenario() {
        UUID clientOpId = UUID.randomUUID();

        // Configure TIMEOUT_AFTER_SUCCESS with 800ms server delay
        ScenarioRequest scenarioRequest = new ScenarioRequest(SimulatorScenario.TIMEOUT_AFTER_SUCCESS, 800L, 0);
        pspClient.put()
                .uri("/api/simulator/scenarios/" + clientOpId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(scenarioRequest)
                .retrieve()
                .toBodilessEntity();

        CreateOperationRequest opRequest = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "15000",
                "INR",
                webhookUrl()
        );

        // Create short-timeout client (200ms read timeout)
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMillis(200));
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        RestClient shortTimeoutClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();

        // Client call times out
        assertThatThrownBy(() -> {
            shortTimeoutClient.post()
                    .uri("/api/provider/operations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(opRequest)
                    .retrieve()
                    .toBodilessEntity();
        }).isInstanceOf(ResourceAccessException.class);

        // Verify that Provider Operation was already durable and SUCCEEDED in DB!
        ResponseEntity<OperationResponse> statusResp = pspClient.get()
                .uri("/api/provider/operations/by-client/" + clientOpId)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusResp.getBody().status()).isEqualTo("SUCCEEDED");
        assertThat(statusResp.getBody().clientOperationId()).isEqualTo(clientOpId);

        // Replay returns 200 OK without re-timing out
        ResponseEntity<OperationResponse> replayResp = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(opRequest)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(replayResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayResp.getBody().providerOperationId()).isEqualTo(statusResp.getBody().providerOperationId());
    }

    @Test
    @DisplayName("DELAYED_WEBHOOK operation responds immediately, webhook delivered after delay")
    void delayedWebhookScenario() {
        UUID clientOpId = UUID.randomUUID();

        // Configure DELAYED_WEBHOOK with 1000ms delay
        ScenarioRequest scenarioRequest = new ScenarioRequest(SimulatorScenario.DELAYED_WEBHOOK, 1000L, 0);
        pspClient.put()
                .uri("/api/simulator/scenarios/" + clientOpId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(scenarioRequest)
                .retrieve()
                .toBodilessEntity();

        CreateOperationRequest opRequest = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "25000",
                "INR",
                webhookUrl()
        );

        ResponseEntity<OperationResponse> response = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(opRequest)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Immediately, webhook is NOT delivered yet
        assertThat(getReceivedPayloadsFor(clientOpId)).isEmpty();

        // After delay, webhook is delivered
        Awaitility.await()
                .atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(getReceivedPayloadsFor(clientOpId)).hasSize(1));
    }

    @Test
    @DisplayName("DUPLICATE_WEBHOOK delivers 2 callbacks with the exact same eventId and payload")
    void duplicateWebhookScenario() throws Exception {
        UUID clientOpId = UUID.randomUUID();

        // Configure DUPLICATE_WEBHOOK
        ScenarioRequest scenarioRequest = new ScenarioRequest(SimulatorScenario.DUPLICATE_WEBHOOK, 0L, 0);
        pspClient.put()
                .uri("/api/simulator/scenarios/" + clientOpId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(scenarioRequest)
                .retrieve()
                .toBodilessEntity();

        CreateOperationRequest opRequest = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "35000",
                "INR",
                webhookUrl()
        );

        ResponseEntity<OperationResponse> response = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(opRequest)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Awaitility waits for 2 deliveries for this clientOperationId
        Awaitility.await()
                .atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> assertThat(getReceivedPayloadsFor(clientOpId)).hasSize(2));

        List<String> payloads = getReceivedPayloadsFor(clientOpId);
        // Verify both payloads carry the identical eventId
        Map<String, Object> payload1 = objectMapper.readValue(payloads.get(0), new TypeReference<>() {});
        Map<String, Object> payload2 = objectMapper.readValue(payloads.get(1), new TypeReference<>() {});

        assertThat(payload1.get("eventId")).isEqualTo(payload2.get("eventId"));
        assertThat(payload1.get("providerOperationId")).isEqualTo(payload2.get("providerOperationId"));
        assertThat(payload1.get("clientOperationId")).isEqualTo(payload2.get("clientOperationId"));
        assertThat(payload1.get("amountMinor")).isEqualTo(payload2.get("amountMinor"));

        // Verify DB has 2 delivery rows for the operation
        assertThat(webhookRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Webhook delivery failure does not roll back or fail provider operation")
    void webhookDeliveryFailureDoesNotAffectOperation() {
        UUID clientOpId = UUID.randomUUID();

        // Webhook URL pointing to an unreachable port
        String deadWebhookUrl = "http://localhost:1/dead-webhook";
        CreateOperationRequest opRequest = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "45000",
                "INR",
                deadWebhookUrl
        );

        ResponseEntity<OperationResponse> response = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(opRequest)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Awaitility waits until webhook row status becomes FAILED
        Awaitility.await()
                .atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> {
                    assertThat(webhookRepository.count()).isEqualTo(1);
                    assertThat(webhookRepository.findAll().getFirst().getStatus().name()).isEqualTo("FAILED");
                });

        // Provider operation remains SUCCEEDED!
        assertThat(operationRepository.count()).isEqualTo(1);
        assertThat(operationRepository.findAll().getFirst().getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Scenario isolation: failure on client A does not affect normal success on client B")
    void scenarioIsolation() {
        UUID clientA = UUID.randomUUID();
        UUID clientB = UUID.randomUUID();

        // Inject TEMPORARY_500 on A
        ScenarioRequest scenarioRequest = new ScenarioRequest(SimulatorScenario.TEMPORARY_500, 0L, 1);
        pspClient.put()
                .uri("/api/simulator/scenarios/" + clientA)
                .contentType(MediaType.APPLICATION_JSON)
                .body(scenarioRequest)
                .retrieve()
                .toBodilessEntity();

        CreateOperationRequest reqA = new CreateOperationRequest(clientA, "CREDIT", "10000", "INR", webhookUrl());
        CreateOperationRequest reqB = new CreateOperationRequest(clientB, "CREDIT", "20000", "INR", webhookUrl());

        // A fails with 500
        ResponseEntity<String> respA = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(reqA)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(String.class);
        assertThat(respA.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // B succeeds with 201
        ResponseEntity<OperationResponse> respB = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(reqB)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(respB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("Operation lookup by ID and by ClientOperationId return expected details")
    void operationLookups() {
        UUID clientOpId = UUID.randomUUID();
        CreateOperationRequest request = new CreateOperationRequest(
                clientOpId,
                "CREDIT",
                "90000",
                "INR",
                null
        );

        ResponseEntity<OperationResponse> created = pspClient.post()
                .uri("/api/provider/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID opId = created.getBody().providerOperationId();

        // Lookup by ID
        ResponseEntity<OperationResponse> byId = pspClient.get()
                .uri("/api/provider/operations/" + opId)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody().providerOperationId()).isEqualTo(opId);

        // Lookup by ClientOperationId
        ResponseEntity<OperationResponse> byClient = pspClient.get()
                .uri("/api/provider/operations/by-client/" + clientOpId)
                .retrieve()
                .onStatus(status -> true, (req, resp) -> {})
                .toEntity(OperationResponse.class);
        assertThat(byClient.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byClient.getBody().providerOperationId()).isEqualTo(opId);
    }
}
