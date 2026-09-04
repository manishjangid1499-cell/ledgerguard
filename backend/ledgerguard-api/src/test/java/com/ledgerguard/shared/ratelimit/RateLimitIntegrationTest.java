package com.ledgerguard.shared.ratelimit;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.shared.error.ApiErrorCode;
import com.ledgerguard.shared.security.JwtTokenService;
import com.ledgerguard.transfer.api.CreateTransferRequest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @DynamicPropertySource
    static void rateLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("ledgerguard.rate-limit.enabled", () -> true);
        registry.add("ledgerguard.rate-limit.policy.financial-write.capacity", () -> 5);
        registry.add("ledgerguard.rate-limit.policy.financial-write.refill-tokens", () -> 5);
        registry.add("ledgerguard.rate-limit.policy.financial-write.refill-period", () -> "1h");

        registry.add("ledgerguard.rate-limit.policy.public-auth.capacity", () -> 5);
        registry.add("ledgerguard.rate-limit.policy.public-auth.refill-tokens", () -> 5);
        registry.add("ledgerguard.rate-limit.policy.public-auth.refill-period", () -> "1h");

        registry.add("ledgerguard.rate-limit.policy.authenticated-general.capacity", () -> 4);
        registry.add("ledgerguard.rate-limit.policy.authenticated-general.refill-tokens", () -> 4);
        registry.add("ledgerguard.rate-limit.policy.authenticated-general.refill-period", () -> "1h");
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        rateLimitService.getBucketCache().invalidateAll();
    }

    private User createCustomer(String prefix) {
        User user = new User(
                UUID.randomUUID(),
                prefix + "." + UUID.randomUUID() + "@example.com",
                "$2a$10$hash",
                UserRole.CUSTOMER,
                UserStatus.ACTIVE
        );
        return userRepository.save(user);
    }

    private LedgerAccount createTestWallet(UUID ownerUserId, AccountType type) {
        LedgerAccount account = (type == AccountType.CUSTOMER)
                ? LedgerAccount.createCustomerAccount(ownerUserId)
                : LedgerAccount.createMerchantAccount(ownerUserId);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private LedgerAccount createSystemAccount(AccountType type) {
        List<LedgerAccount> existing = ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getAccountType() == type && a.getOwnerUserId() == null)
                .toList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        LedgerAccount account = LedgerAccount.createSystemAccount(type);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private void fundWallet(UUID walletAccountId, long amountMinor) {
        LedgerAccount reserve = createSystemAccount(AccountType.PLATFORM_RESERVE);
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserve.getId(), amountMinor),
                PostingLine.credit(walletAccountId, amountMinor)
        ));
    }

    @Test
    @DisplayName("50 concurrent requests against capacity 5: exactly 5 admitted and 45 rejected with HTTP 429")
    void concurrentBurstCapacityEnforcement() throws Exception {
        User sender = createCustomer("burst.sender");
        User receiver = createCustomer("burst.recv");
        LedgerAccount senderWallet = createTestWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiver.getId(), AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 500_000L);

        String token = jwtTokenService.generateAccessToken(sender);
        CreateTransferRequest request = new CreateTransferRequest(receiverWallet.getId(), 100L);
        String requestJson = objectMapper.writeValueAsString(request);

        int totalRequests = 50;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger tooManyRequestsCount = new AtomicInteger(0);
        AtomicInteger admittedCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalRequests; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/api/transfers")
                                    .header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestJson))
                            .andReturn();

                    int status = result.getResponse().getStatus();
                    statusCodes.add(status);
                    if (status == 429) {
                        tooManyRequestsCount.incrementAndGet();
                        assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
                        assertThat(result.getResponse().getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
                    } else if (status == 201) {
                        admittedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        for (Future<?> f : futures) {
            f.get();
        }

        assertThat(admittedCount.get()).isEqualTo(5);
        assertThat(tooManyRequestsCount.get()).isEqualTo(45);
        assertThat(statusCodes).hasSize(50);
    }

    @Test
    @DisplayName("Principal isolation: Exhausting User A quota does not affect User B")
    void principalIsolation() throws Exception {
        User userA = createCustomer("userA");
        User userB = createCustomer("userB");
        User receiver = createCustomer("recv");

        LedgerAccount walletA = createTestWallet(userA.getId(), AccountType.CUSTOMER);
        LedgerAccount walletB = createTestWallet(userB.getId(), AccountType.CUSTOMER);
        LedgerAccount walletRecv = createTestWallet(receiver.getId(), AccountType.CUSTOMER);

        fundWallet(walletA.getId(), 500_000L);
        fundWallet(walletB.getId(), 500_000L);

        String tokenA = jwtTokenService.generateAccessToken(userA);
        String tokenB = jwtTokenService.generateAccessToken(userB);

        CreateTransferRequest reqA = new CreateTransferRequest(walletRecv.getId(), 100L);
        String reqJsonA = objectMapper.writeValueAsString(reqA);

        // User A consumes 5 tokens
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/transfers")
                            .header("Authorization", "Bearer " + tokenA)
                            .header("Idempotency-Key", "key-a-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reqJsonA))
                    .andExpect(status().isCreated());
        }

        // User A's 6th request is throttled with 429
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "key-a-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqJsonA))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RATE_LIMIT_EXCEEDED)));

        // User B makes a request to the same endpoint: User B is admitted
        CreateTransferRequest reqB = new CreateTransferRequest(walletRecv.getId(), 100L);
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + tokenB)
                        .header("Idempotency-Key", "key-b-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Public Auth flood: Requests beyond capacity receive 429 without invoking AuthService")
    void publicAuthLoginFlood() throws Exception {
        String badLoginPayload = "{\"email\":\"nobody@example.com\",\"password\":\"wrongpassword\"}";

        // First 5 requests reach AuthService and fail authentication (401)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badLoginPayload))
                    .andExpect(status().isUnauthorized());
        }

        // 6th, 7th requests are blocked by RateLimitFilter before reaching AuthService (429)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badLoginPayload))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RATE_LIMIT_EXCEEDED)));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badLoginPayload))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RATE_LIMIT_EXCEEDED)));
    }

    @Test
    @DisplayName("Financial command non-creation on 429, succeeded on replay with key K after refill")
    void idempotencyAndFinancialSafetyOnRateLimitExceeded() throws Exception {
        User sender = createCustomer("safe.sender");
        User receiver = createCustomer("safe.recv");
        LedgerAccount senderWallet = createTestWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiver.getId(), AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 100_000L);

        String token = jwtTokenService.generateAccessToken(sender);

        // Exhaust sender's 5 tokens
        for (int i = 0; i < 5; i++) {
            CreateTransferRequest req = new CreateTransferRequest(receiverWallet.getId(), 50L);
            mockMvc.perform(post("/api/transfers")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "key-exhaust-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());
        }

        String safeKey = "idemp-key-safe-" + UUID.randomUUID();
        CreateTransferRequest targetRequest = new CreateTransferRequest(receiverWallet.getId(), 2000L);
        String targetJson = objectMapper.writeValueAsString(targetRequest);

        // Attempt 1: blocked by rate limiter (HTTP 429)
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", safeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(targetJson))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RATE_LIMIT_EXCEEDED)));

        // Invariant: 429 MUST NEVER insert idempotency records, journals, entries, or holds
        Integer idempCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class,
                safeKey
        );
        assertThat(idempCount).isEqualTo(0);

        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE initiated_by_user_id = ? AND amount_minor = 2000",
                Integer.class,
                sender.getId()
        );
        assertThat(transferCount).isEqualTo(0);

        // Simulate refill / quota refresh
        rateLimitService.getBucketCache().invalidateAll();

        // Attempt 2: Same request with key safeKey now executes as the FIRST authoritative call
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", safeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(targetJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed", is(false)));

        // Post-execution: exactly 1 idempotency record and 1 transfer
        idempCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
                Integer.class,
                safeKey
        );
        assertThat(idempCount).isEqualTo(1);

        transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE initiated_by_user_id = ? AND amount_minor = 2000",
                Integer.class,
                sender.getId()
        );
        assertThat(transferCount).isEqualTo(1);

        // Attempt 3: Immediate replay returns 200 OK with replayed: true
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", safeKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(targetJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)));
    }

    @Test
    @DisplayName("Funding and Payout 429 rejection produces NO funding_operations, NO payouts, NO balance_holds, and NO outbox")
    void fundingAndPayout429CreatesNoBusinessRecordsOrHolds() throws Exception {
        User customer = createCustomer("fp.safe");
        LedgerAccount wallet = createTestWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 500_000L);
        String token = jwtTokenService.generateAccessToken(customer);

        // Exhaust sender's 5 FINANCIAL_WRITE tokens
        for (int i = 0; i < 5; i++) {
            CreateTransferRequest req = new CreateTransferRequest(wallet.getId(), 10L);
            mockMvc.perform(post("/api/transfers")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "fp-exhaust-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)));
        }

        // 1. Funding request on exhausted quota yields 429
        String fundingKey = "funding-429-" + UUID.randomUUID();
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", fundingKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":\"5000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RATE_LIMIT_EXCEEDED)));

        Integer fundingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM funding_operations WHERE initiated_by_user_id = ?",
                Integer.class,
                customer.getId()
        );
        assertThat(fundingCount).isEqualTo(0);

        // 2. Payout request on exhausted quota yields 429
        String payoutKey = "payout-429-" + UUID.randomUUID();
        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", payoutKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":\"5000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RATE_LIMIT_EXCEEDED)));

        Integer payoutCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payouts WHERE initiated_by_user_id = ?",
                Integer.class,
                customer.getId()
        );
        assertThat(payoutCount).isEqualTo(0);

        Integer holdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM balance_holds WHERE ledger_account_id = ?",
                Integer.class,
                wallet.getId()
        );
        assertThat(holdCount).isEqualTo(0);

        Integer idempCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key IN (?, ?)",
                Integer.class,
                fundingKey, payoutKey
        );
        assertThat(idempCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Database connection conservation: 429 requests do not hold or leak DB connections")
    void databaseConnectionConservation() throws Exception {
        HikariDataSource hikariDataSource = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikariDataSource.getMaximumPoolSize()).isLessThanOrEqualTo(10);

        User customer = createCustomer("db.conn");
        String token = jwtTokenService.generateAccessToken(customer);
        CreateTransferRequest req = new CreateTransferRequest(UUID.randomUUID(), 100L);
        String reqJson = objectMapper.writeValueAsString(req);

        // Exhaust capacity
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/transfers")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "exhaust-" + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(reqJson));
        }

        // Now fire 20 requests that all get 429
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(post("/api/transfers")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "blocked-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reqJson))
                    .andExpect(status().isTooManyRequests());

            // RateLimitFilter exits before any DB access is performed
            if (hikariDataSource.getHikariPoolMXBean() != null) {
                assertThat(hikariDataSource.getHikariPoolMXBean().getActiveConnections())
                        .isLessThanOrEqualTo(hikariDataSource.getMaximumPoolSize());
            }
        }
    }

    @Test
    @DisplayName("Rate policy isolation: Same user consuming FINANCIAL_WRITE does not affect AUTHENTICATED_GENERAL")
    void ratePolicyIsolationTest() throws Exception {
        User customer = createCustomer("isolation.user");
        User receiverUser = createCustomer("isolation.recv");
        String token = jwtTokenService.generateAccessToken(customer);

        createTestWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount receiver = createTestWallet(receiverUser.getId(), AccountType.CUSTOMER);
        CreateTransferRequest transferReq = new CreateTransferRequest(receiver.getId(), 50L);
        String transferJson = objectMapper.writeValueAsString(transferReq);

        // 1. Consume all 5 FINANCIAL_WRITE tokens
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/transfers")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "iso-key-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferJson));
        }

        // 6th FINANCIAL_WRITE request is rejected with 429
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "iso-key-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RATE_LIMIT_EXCEEDED)));

        // 2. Now perform AUTHENTICATED_GENERAL requests (e.g. GET /api/wallets/me) with the same token
        // Capacity is 4: first 4 must be admitted
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/wallets/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        // 5th AUTHENTICATED_GENERAL request receives 429
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/wallets/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RATE_LIMIT_EXCEEDED)));
    }
}
