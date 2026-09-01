package com.ledgerguard.payout.api;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.shared.security.JwtTokenService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PayoutControllerIntegrationTest extends AbstractIntegrationTest {

    private static HttpServer mockPspServer;
    private static int mockPspPort;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @FunctionalInterface
    interface HandlerFn {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static volatile HandlerFn currentHandler;

    @DynamicPropertySource
    static void configurePspProperties(DynamicPropertyRegistry registry) {
        registry.add("ledgerguard.psp.base-url", () -> "http://localhost:" + mockPspPort);
        registry.add("ledgerguard.psp.connect-timeout-ms", () -> 2000);
        registry.add("ledgerguard.psp.read-timeout-ms", () -> 2000);
    }

    @BeforeAll
    static void startMockPspServer() throws IOException {
        mockPspServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPspPort = mockPspServer.getAddress().getPort();
        mockPspServer.createContext("/api/provider/operations", exchange -> {
            HandlerFn handler = currentHandler;
            if (handler != null) {
                handler.handle(exchange);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        });
        mockPspServer.setExecutor(Executors.newCachedThreadPool());
        mockPspServer.start();
    }

    @AfterAll
    static void stopMockPspServer() {
        if (mockPspServer != null) {
            mockPspServer.stop(0);
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository snapshotRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private User customerUser;
    private String customerToken;
    private User merchantUser;
    private String merchantToken;
    private User opsUser;
    private String opsToken;
    private LedgerAccount customerAccount;
    private LedgerAccount merchantAccount;
    private LedgerAccount pspClearingAccount;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        currentHandler = null;
        Timestamp now = Timestamp.from(Instant.now());

        // Canonical clearing
        List<LedgerAccount> clearings = ledgerAccountRepository.findAllByAccountType(AccountType.PSP_CLEARING);
        for (LedgerAccount ca : clearings) {
            if (ca.getStatus() == AccountStatus.ACTIVE) {
                ca.close(Instant.now());
                ledgerAccountRepository.saveAndFlush(ca);
            }
        }
        pspClearingAccount = LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING);
        ledgerAccountRepository.saveAndFlush(pspClearingAccount);

        // Create Customer User & Account
        customerUser = userRepository.save(new User(
                UUID.randomUUID(), "cust-" + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE
        ));
        customerToken = jwtTokenService.generateAccessToken(customerUser);

        // Create Merchant User & Account
        merchantUser = userRepository.save(new User(
                UUID.randomUUID(), "merch-" + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE
        ));
        merchantToken = jwtTokenService.generateAccessToken(merchantUser);

        // Create Ops User
        opsUser = userRepository.save(new User(
                UUID.randomUUID(), "ops-" + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE
        ));
        opsToken = jwtTokenService.generateAccessToken(opsUser);

        // Accounts
        UUID custAccId = UUID.randomUUID();
        UUID merchAccId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                custAccId, customerUser.getId(), now, now
        );
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'MERCHANT', 'INR', 'ACTIVE', ?, ?)",
                merchAccId, merchantUser.getId(), now, now
        );

        // Snapshots (Customer = 50000, Merchant = 50000, PSP_CLEARING = 100000)
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = 50000 WHERE ledger_account_id = ?",
                custAccId
        );
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = 50000 WHERE ledger_account_id = ?",
                merchAccId
        );
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = 100000 WHERE ledger_account_id = ?",
                pspClearingAccount.getId()
        );

        customerAccount = ledgerAccountRepository.findById(custAccId).orElseThrow();
        merchantAccount = ledgerAccountRepository.findById(merchAccId).orElseThrow();
    }

    private void respondJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    @DisplayName("CUSTOMER requesting payout with valid request receives 201 Created")
    void customerCanRequestPayout() throws Exception {
        currentHandler = exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", "10000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "cust-payout-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payoutId", notNullValue()))
                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.amountMinor", is("10000")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.replayed", is(false)));
    }

    @Test
    @DisplayName("MERCHANT requesting payout with valid request receives 201 Created")
    void merchantCanRequestPayout() throws Exception {
        currentHandler = exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", "10000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", "merch-payout-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payoutId", notNullValue()))
                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.amountMinor", is("10000")));
    }

    @Test
    @DisplayName("OPS role and unauthenticated requests are rejected with 403 and 401")
    void authorizationRulesEnforced() throws Exception {
        // OPS role -> 403 Forbidden
        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + opsToken)
                        .header("Idempotency-Key", "ops-payout-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isForbidden());

        // Unauthenticated -> 401 Unauthorized
        mockMvc.perform(post("/api/payouts")
                        .header("Idempotency-Key", "no-auth-payout-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Idempotency key validation enforces required and length bounds")
    void idempotencyKeyValidationEnforced() throws Exception {
        // Missing Idempotency-Key header -> 400 Bad Request
        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isBadRequest());

        // Blank Idempotency-Key header -> 400 Bad Request
        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isBadRequest());

        // 129-character Idempotency-Key -> 400 Bad Request
        String longKey = "a".repeat(129);
        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", longKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isBadRequest());

        // 128-character Idempotency-Key -> accepted
        currentHandler = exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", "10000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        String maxKey = "a".repeat(128);
        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", maxKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Same Idempotency-Key replay returns 200 OK after SUCCEEDED")
    void matchingReplayReturns200Ok() throws Exception {
        currentHandler = exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", "10000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        String replayKey = "replay-key-" + UUID.randomUUID();

        // First call -> 201 Created
        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", replayKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed", is(false)));

        // Replay -> 200 OK
        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", replayKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": \"10000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)))
                .andExpect(jsonPath("$.status", is("SUCCEEDED")));
    }
}
