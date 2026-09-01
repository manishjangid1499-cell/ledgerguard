package com.ledgerguard.funding.api;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.shared.security.JwtTokenService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FundingControllerIntegrationTest extends AbstractIntegrationTest {

    private static HttpServer mockPspServer;
    private static int mockPspPort;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final AtomicBoolean pspShouldReturn500 = new AtomicBoolean(false);

    @BeforeAll
    static void startMockPspServer() throws IOException {
        mockPspServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPspPort = mockPspServer.getAddress().getPort();

        mockPspServer.createContext("/api/provider/operations", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (pspShouldReturn500.get()) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }

                Map<String, Object> reqBody;
                try (InputStream is = exchange.getRequestBody()) {
                    reqBody = objectMapper.readValue(is, Map.class);
                }

                UUID clientOpId = UUID.fromString((String) reqBody.get("clientOperationId"));
                String reqAmount = (String) reqBody.get("amountMinor");

                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "CREDIT",
                        "amountMinor", reqAmount,
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );

                byte[] respBytes = objectMapper.writeValueAsBytes(respMap);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
                exchange.close();
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

    @DynamicPropertySource
    static void pspProperties(DynamicPropertyRegistry registry) {
        registry.add("ledgerguard.psp.base-url", () -> "http://localhost:" + mockPspPort);
        registry.add("ledgerguard.psp.connect-timeout-ms", () -> 1000);
        registry.add("ledgerguard.psp.read-timeout-ms", () -> 1000);
    }

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private FundingOperationRepository fundingOperationRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        pspShouldReturn500.set(false);

        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        List<LedgerAccount> clearingAccounts = ledgerAccountRepository.findAllByAccountType(AccountType.PSP_CLEARING);
        for (LedgerAccount ca : clearingAccounts) {
            if (ca.getStatus() == AccountStatus.ACTIVE) {
                ca.close(Instant.now());
                ledgerAccountRepository.saveAndFlush(ca);
            }
        }
        LedgerAccount canonicalClearing = LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING);
        ledgerAccountRepository.saveAndFlush(canonicalClearing);
    }

    @Test
    @DisplayName("CUSTOMER role: successfully funds wallet and receives 201 Created")
    void customerFundingSuccess() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);
        createAccount(customer.getId(), AccountType.CUSTOMER, AccountStatus.ACTIVE, "INR");

        String token = jwtTokenService.generateAccessToken(customer);
        String idempotencyKey = "key-fund-201";

        FundingRequest request = new FundingRequest("10000");

        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fundingId", notNullValue()))
                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.amountMinor", is("10000")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.providerOperationId", notNullValue()))
                .andExpect(jsonPath("$.journalTransactionId", notNullValue()))
                .andExpect(jsonPath("$.replayed", is(false)));

        // Matching replay -> 200 OK
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.replayed", is(true)));
    }

    @Test
    @DisplayName("Unconfirmed funding: returns 202 Accepted with PROCESSING status")
    void unconfirmedFundingReturns202() throws Exception {
        pspShouldReturn500.set(true);

        User customer = new User(UUID.randomUUID(), "cust.proc." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);
        createAccount(customer.getId(), AccountType.CUSTOMER, AccountStatus.ACTIVE, "INR");

        String token = jwtTokenService.generateAccessToken(customer);
        String idempotencyKey = "key-fund-202";

        FundingRequest request = new FundingRequest("10000");

        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fundingId", notNullValue()))
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.amountMinor", is("10000")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.providerOperationId", nullValue()))
                .andExpect(jsonPath("$.journalTransactionId", nullValue()));
    }

    @Test
    @DisplayName("MERCHANT and OPS roles are forbidden (403), unauthenticated is 401")
    void authorizationChecks() throws Exception {
        User merchant = new User(UUID.randomUUID(), "merch." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        User ops = new User(UUID.randomUUID(), "ops." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(merchant);
        userRepository.save(ops);

        String merchantToken = jwtTokenService.generateAccessToken(merchant);
        String opsToken = jwtTokenService.generateAccessToken(ops);

        FundingRequest request = new FundingRequest("10000");

        // Merchant -> 403
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", "key-merch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // OPS -> 403
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + opsToken)
                        .header("Idempotency-Key", "key-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // Unauthenticated -> 401
        mockMvc.perform(post("/api/funding")
                        .header("Idempotency-Key", "key-no-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Idempotency-Key validation: missing/blank/oversized -> 400 Bad Request, length 128 -> accepted")
    void idempotencyKeyValidation() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.key." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);
        createAccount(customer.getId(), AccountType.CUSTOMER, AccountStatus.ACTIVE, "INR");

        String token = jwtTokenService.generateAccessToken(customer);
        FundingRequest request = new FundingRequest("10000");

        // Missing Idempotency-Key
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_FUNDING")));

        // Blank Idempotency-Key
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_FUNDING")));

        // Oversized Idempotency-Key (> 128 chars)
        String longKey = "a".repeat(129);
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", longKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_FUNDING")));

        // Exact 128 chars -> Accepted
        String key128 = "a".repeat(128);
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key128)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Amount validation: blank, 0, negative, fractional, or non-integral -> 400 Bad Request")
    void amountValidation() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.amt." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);
        createAccount(customer.getId(), AccountType.CUSTOMER, AccountStatus.ACTIVE, "INR");

        String token = jwtTokenService.generateAccessToken(customer);

        // Blank amount
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "key-amt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FundingRequest(""))))
                .andExpect(status().isBadRequest());

        // Zero amount
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "key-amt-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FundingRequest("0"))))
                .andExpect(status().isBadRequest());

        // Negative amount
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "key-amt-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FundingRequest("-500"))))
                .andExpect(status().isBadRequest());

        // Fractional amount
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "key-amt-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FundingRequest("100.50"))))
                .andExpect(status().isBadRequest());

        // Non-numeric amount
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "key-amt-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FundingRequest("abc"))))
                .andExpect(status().isBadRequest());
    }

    private UUID createAccount(UUID ownerUserId, AccountType type, AccountStatus status, String currency) {
        UUID accId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                accId, ownerUserId, type.name(), currency, status.name(), now, now
        );
        return accId;
    }
}
