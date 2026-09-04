package com.ledgerguard.provider.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.payout.application.CreatePayoutCommand;
import com.ledgerguard.payout.application.PayoutResult;
import com.ledgerguard.payout.application.PayoutService;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.domain.ProviderEvent;
import com.ledgerguard.provider.domain.ProviderProcessingStatus;
import com.ledgerguard.provider.infrastructure.ProviderEventRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test proving the real external callback boundary:
 * LedgerGuard PspClient
 *    -> real HTTP to mock PSP simulator
 *    -> PSP simulates TIMEOUT_AFTER_SUCCESS on synchronous response
 *    -> LedgerGuard Payout remains PROCESSING with ACTIVE hold
 *    -> PSP dispatches signed HTTP webhook callback to LedgerGuard's configured webhookUrl
 *    -> LedgerGuard HTTP ingress receives and authenticates HMAC-SHA256
 *    -> provider_events persisted and ordered cursor processes settlement
 *    -> Payout transitions to SUCCEEDED and BalanceHold is CONSUMED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "server.port=8089")
class ProviderRealCallbackE2EIntegrationTest extends AbstractIntegrationTest {

    private static HttpServer mockPspServer;
    private static int mockPspPort;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private BalanceHoldRepository balanceHoldRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private FundingOperationRepository fundingOperationRepository;

    @Autowired
    private ProviderEventRepository providerEventRepository;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private ProviderWebhookService providerWebhookService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startMockPspServer() throws IOException {
        mockPspServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPspPort = mockPspServer.getAddress().getPort();
        mockPspServer.setExecutor(Executors.newCachedThreadPool());
        mockPspServer.start();
    }

    @AfterAll
    static void stopMockPspServer() {
        if (mockPspServer != null) {
            mockPspServer.stop(0);
        }
    }

    @DynamicPropertySource
    static void configureDynamicPsp(DynamicPropertyRegistry registry) {
        registry.add("ledgerguard.psp.base-url", () -> "http://localhost:" + mockPspPort);
        registry.add("ledgerguard.psp.connect-timeout-ms", () -> 2000);
        registry.add("ledgerguard.psp.read-timeout-ms", () -> 300);
        registry.add("ledgerguard.psp.webhook-url", () -> "http://localhost:8089/api/provider/webhooks");
    }

    private UUID customerUserId;
    private UUID customerAccountId;
    private LedgerAccount pspClearingAccount;

    @BeforeEach
    void setUp() {
        // Ensure canonical PSP_CLEARING exists
        List<LedgerAccount> clearings = ledgerAccountRepository.findAllByAccountType(AccountType.PSP_CLEARING);
        for (LedgerAccount ca : clearings) {
            if (ca.getStatus() == AccountStatus.ACTIVE) {
                ca.close(Instant.now());
                ledgerAccountRepository.saveAndFlush(ca);
            }
        }
        pspClearingAccount = LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING);
        ledgerAccountRepository.saveAndFlush(pspClearingAccount);

        customerUserId = UUID.randomUUID();
        customerAccountId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Users
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                customerUserId, "p22-e2e-" + customerUserId + "@example.com", now, now
        );

        // Customer Ledger Account
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                customerAccountId, customerUserId, now, now
        );

        // Pre-fund customer account with 10,000 paise via settled FundingOperation
        UUID fundingId = UUID.randomUUID();
        UUID fundingProviderOpId = UUID.randomUUID();
        FundingOperation funding = new FundingOperation(
                fundingId, customerUserId, customerAccountId, 10000L, "INR", Instant.now()
        );
        fundingOperationRepository.saveAndFlush(funding);
        funding.prepareSubmission(Instant.now().plusSeconds(10));
        fundingOperationRepository.saveAndFlush(funding);

        String fundingJson = """
                {
                    "eventId": "%s",
                    "eventSequence": 1,
                    "eventType": "PROVIDER_OPERATION_SUCCEEDED",
                    "providerOperationId": "%s",
                    "clientOperationId": "%s",
                    "operationType": "CREDIT",
                    "status": "SUCCEEDED",
                    "amountMinor": "10000",
                    "currency": "INR",
                    "occurredAt": "%s"
                }
                """.formatted(UUID.randomUUID(), fundingProviderOpId, fundingId, Instant.now());

        long ts = Instant.now().getEpochSecond();
        byte[] body = fundingJson.getBytes(StandardCharsets.UTF_8);
        String sig = signHmac(ts, body, RUNTIME_WEBHOOK_SECRET);
        providerWebhookService.handleWebhook(String.valueOf(ts), sig, body);
    }

    @Test
    @DisplayName("End-to-End: TIMEOUT_AFTER_SUCCESS payout receives real HTTP callback and settles asynchronously")
    void timeoutAfterSuccessPayoutReceivesRealHttpCallbackAndSettles() throws Exception {
        UUID providerOperationId = UUID.randomUUID();
        long amountMinor = 4000L;

        // Configure mock PSP handler to simulate TIMEOUT_AFTER_SUCCESS:
        // Delay synchronous HTTP response past 300ms read timeout, then dispatch signed webhook to configured webhookUrl
        try {
            mockPspServer.removeContext("/api/provider/operations");
        } catch (Exception ignored) {}

        mockPspServer.createContext("/api/provider/operations", exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                byte[] requestBytes = is.readAllBytes();
                JsonNode reqNode = objectMapper.readTree(requestBytes);
                String callbackUrl = reqNode.path("webhookUrl").asText();
                UUID clientOpId = UUID.fromString(reqNode.path("clientOperationId").asText());

                // Dispatch signed webhook asynchronously in background thread
                Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                    try {
                        UUID eventId = UUID.randomUUID();
                        String webhookPayload = """
                                {
                                    "eventId": "%s",
                                    "eventSequence": 1,
                                    "eventType": "PROVIDER_OPERATION_SUCCEEDED",
                                    "providerOperationId": "%s",
                                    "clientOperationId": "%s",
                                    "operationType": "DEBIT",
                                    "status": "SUCCEEDED",
                                    "amountMinor": "%d",
                                    "currency": "INR",
                                    "occurredAt": "%s"
                                }
                                """.formatted(eventId, providerOperationId, clientOpId, amountMinor, Instant.now());

                        long webhookTs = Instant.now().getEpochSecond();
                        byte[] payloadBytes = webhookPayload.getBytes(StandardCharsets.UTF_8);
                        String webhookSig = signHmac(webhookTs, payloadBytes, RUNTIME_WEBHOOK_SECRET);

                        HttpClient client = HttpClient.newHttpClient();
                        HttpRequest webhookRequest = HttpRequest.newBuilder()
                                .uri(URI.create(callbackUrl))
                                .header("Content-Type", "application/json")
                                .header("X-PSP-Webhook-Timestamp", String.valueOf(webhookTs))
                                .header("X-PSP-Webhook-Signature", webhookSig)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(payloadBytes))
                                .build();

                        client.send(webhookRequest, HttpResponse.BodyHandlers.ofString());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, 1800, TimeUnit.MILLISECONDS);

                // Delay synchronous response by 600ms to trigger PspClient read-timeout (300ms)
                Thread.sleep(600);

                String pspResponse = """
                        {
                            "id": "%s",
                            "clientOperationId": "%s",
                            "operationType": "DEBIT",
                            "amountMinor": "%d",
                            "currency": "INR",
                            "status": "SUCCEEDED",
                            "createdAt": "%s",
                            "completedAt": "%s",
                            "replayed": false
                        }
                        """.formatted(providerOperationId, clientOpId, amountMinor, Instant.now(), Instant.now());

                byte[] respBytes = pspResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, respBytes.length);
                exchange.getResponseBody().write(respBytes);
                exchange.getResponseBody().close();
            } catch (Exception ex) {
                // transport closed due to client timeout
            }
        });

        // Execute Payout via PayoutService (creates hold, calls PspClient over real HTTP)
        CreatePayoutCommand command = new CreatePayoutCommand(
                customerUserId, UUID.randomUUID().toString(), Money.inr(amountMinor)
        );
        PayoutResult execResult = payoutService.requestPayout(command);

        // Synchronous call should time out and return HTTP 202 ACCEPTED with status UNKNOWN
        assertThat(execResult.status()).isEqualTo(PayoutStatus.UNKNOWN);

        // Verify initial state: BalanceHold is ACTIVE, 0 payout journals posted
        BalanceHold hold = balanceHoldRepository.findById(execResult.balanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        Integer journalsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN payouts p ON p.journal_transaction_id = jt.id WHERE p.id = ?",
                Integer.class, execResult.payoutId()
        );
        assertThat(journalsBefore).isEqualTo(0);

        // Await real HTTP callback dispatch, authentication, and asynchronous settlement
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Payout settled = payoutRepository.findById(execResult.payoutId()).orElseThrow();
            assertThat(settled.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
        });

        // Verify final financial invariants
        Payout finalPayout = payoutRepository.findById(execResult.payoutId()).orElseThrow();
        assertThat(finalPayout.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(finalPayout.getProviderOperationId()).isEqualTo(providerOperationId);

        BalanceHold finalHold = balanceHoldRepository.findById(finalPayout.getBalanceHoldId()).orElseThrow();
        assertThat(finalHold.getStatus()).isEqualTo(HoldStatus.CONSUMED);

        // Exactly 1 double-entry settlement journal exists
        Integer journalsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN payouts p ON p.journal_transaction_id = jt.id WHERE p.id = ?",
                Integer.class, finalPayout.getId()
        );
        assertThat(journalsAfter).isEqualTo(1);

        // Provider event was durably recorded and marked APPLIED
        Integer appliedEventsCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_events WHERE provider_operation_id = ? AND processing_status = 'APPLIED'",
                Integer.class, providerOperationId
        );
        assertThat(appliedEventsCount).isEqualTo(1);
    }

    private static String signHmac(long timestamp, byte[] body, String secret) {
        try {
            byte[] canonical = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] toSign = new byte[canonical.length + body.length];
            System.arraycopy(canonical, 0, toSign, 0, canonical.length);
            System.arraycopy(body, 0, toSign, canonical.length, body.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawHmac = mac.doFinal(toSign);
            return "sha256=" + HexFormat.of().formatHex(rawHmac);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
