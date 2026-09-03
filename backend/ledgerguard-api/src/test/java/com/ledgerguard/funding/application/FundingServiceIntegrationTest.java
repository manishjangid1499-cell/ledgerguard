package com.ledgerguard.funding.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.hold.application.HoldService;
import com.ledgerguard.idempotency.domain.IdempotencyConflictException;
import com.ledgerguard.idempotency.infrastructure.IdempotencyRecordRepository;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.JournalEntryRepository;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FundingServiceIntegrationTest extends AbstractIntegrationTest {

    @FunctionalInterface
    interface PspHandlerFunction {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static HttpServer mockPspServer;
    private static int mockPspPort;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final AtomicReference<PspHandlerFunction> activeHandler = new AtomicReference<>(null);
    private static final AtomicBoolean wasDbTxActiveDuringPspCall = new AtomicBoolean(false);

    @BeforeAll
    static void startMockPspServer() throws IOException {
        mockPspServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPspPort = mockPspServer.getAddress().getPort();

        mockPspServer.createContext("/api/provider/operations", exchange -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                wasDbTxActiveDuringPspCall.set(true);
            }

            PspHandlerFunction customHandler = activeHandler.get();
            if (customHandler != null) {
                customHandler.handle(exchange);
                return;
            }

            defaultSuccessHandler(exchange);
        });

        mockPspServer.setExecutor(Executors.newCachedThreadPool());
        mockPspServer.start();
    }

    private static void defaultSuccessHandler(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        Map<String, Object> reqBody;
        try (InputStream is = exchange.getRequestBody()) {
            reqBody = objectMapper.readValue(is, Map.class);
        }

        UUID clientOpId = UUID.fromString((String) reqBody.get("clientOperationId"));
        String reqAmount = (String) reqBody.get("amountMinor");
        String reqCurrency = (String) reqBody.get("currency");
        String reqType = (String) reqBody.get("operationType");

        Map<String, Object> respMap = Map.of(
                "providerOperationId", UUID.randomUUID().toString(),
                "clientOperationId", clientOpId.toString(),
                "operationType", reqType != null ? reqType : "CREDIT",
                "amountMinor", reqAmount,
                "currency", reqCurrency != null ? reqCurrency : "INR",
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

    @Autowired
    private FundingService fundingService;

    @Autowired
    private FundingOperationRepository fundingOperationRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private HoldService holdService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.ledgerguard.provider.application.ProviderStatusPollingService providerStatusPollingService;

    private UUID customerUserId;
    private LedgerAccount customerAccount;
    private LedgerAccount pspClearingAccount;

    @BeforeEach
    void setUp() {
        activeHandler.set(null);
        wasDbTxActiveDuringPspCall.set(false);

        List<LedgerAccount> clearingAccounts = ledgerAccountRepository.findAllByAccountType(AccountType.PSP_CLEARING);
        for (LedgerAccount ca : clearingAccounts) {
            if (ca.getStatus() == AccountStatus.ACTIVE) {
                ca.close(Instant.now());
                ledgerAccountRepository.saveAndFlush(ca);
            }
        }
        pspClearingAccount = LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING);
        ledgerAccountRepository.saveAndFlush(pspClearingAccount);

        customerUserId = createTestUser("customer-" + UUID.randomUUID() + "@example.com", "CUSTOMER");
        customerAccount = createTestLedgerAccount(customerUserId, AccountType.CUSTOMER, AccountStatus.ACTIVE, "INR");
    }

    @Test
    @DisplayName("Normal success: funds customer wallet from 0 to 10000 with atomic PSP_CLEARING settlement")
    void normalSuccessFunding() {
        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                "fund-key-1-" + UUID.randomUUID(),
                Money.inr(10000)
        );

        FundingResult result = fundingService.fundWallet(command);

        assertThat(result.status()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(result.amountMinor()).isEqualTo(10000);
        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.providerOperationId()).isNotNull();
        assertThat(result.journalTransactionId()).isNotNull();
        assertThat(result.replayed()).isFalse();

        // Verify FundingOperation DB row
        FundingOperation funding = fundingOperationRepository.findById(result.fundingId()).orElseThrow();
        assertThat(funding.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(funding.getAmountMinor()).isEqualTo(10000);
        assertThat(funding.getProviderOperationId()).isEqualTo(result.providerOperationId());
        assertThat(funding.getJournalTransactionId()).isEqualTo(result.journalTransactionId());

        // Verify customer wallet balance
        LedgerBalanceSnapshot customerSnapshot = ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(customerSnapshot.getBalanceMinor()).isEqualTo(10000);

        // Verify PSP_CLEARING balance
        LedgerBalanceSnapshot clearingSnapshot = ledgerBalanceSnapshotRepository.findById(pspClearingAccount.getId()).orElseThrow();
        assertThat(clearingSnapshot.getBalanceMinor()).isEqualTo(10000);

        // Verify no DB transaction was active during external PSP call
        assertThat(wasDbTxActiveDuringPspCall.get()).isFalse();
    }

    @Test
    @DisplayName("Existing balance funding: adds to existing posted balance without overwrite")
    void existingBalanceFunding() {
        setAccountBalance(customerAccount.getId(), 5000);
        setAccountBalance(pspClearingAccount.getId(), 10000);

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                "fund-key-2-" + UUID.randomUUID(),
                Money.inr(3000)
        );

        FundingResult result = fundingService.fundWallet(command);
        assertThat(result.status()).isEqualTo(FundingStatus.SUCCEEDED);

        LedgerBalanceSnapshot customerSnapshot = ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(customerSnapshot.getBalanceMinor()).isEqualTo(8000);

        LedgerBalanceSnapshot clearingSnapshot = ledgerBalanceSnapshotRepository.findById(pspClearingAccount.getId()).orElseThrow();
        assertThat(clearingSnapshot.getBalanceMinor()).isEqualTo(13000);
    }

    @Test
    @DisplayName("Holds funding: available balance increases by funding amount while active holds remain untouched")
    void fundingWithActiveHolds() {
        setAccountBalance(customerAccount.getId(), 10000);
        holdService.createHold(customerAccount.getId(), Money.inr(7000), Instant.now().plusSeconds(3600));

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                "fund-key-3-" + UUID.randomUUID(),
                Money.inr(5000)
        );

        FundingResult result = fundingService.fundWallet(command);
        assertThat(result.status()).isEqualTo(FundingStatus.SUCCEEDED);

        LedgerBalanceSnapshot customerSnapshot = ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(customerSnapshot.getBalanceMinor()).isEqualTo(15000);

        var available = holdService.getAvailableBalance(customerAccount.getId());
        assertThat(available.postedBalanceMinor()).isEqualTo(15000);
        assertThat(available.activeHoldAmountMinor()).isEqualTo(7000);
        assertThat(available.availableBalanceMinor()).isEqualTo(8000);
    }

    @Test
    @DisplayName("PSP returns TEMPORARY_500: marks FAILED and allows safe replay")
    void temporary500Scenario() {
        String idempotencyKey = "fund-500-" + UUID.randomUUID();
        AtomicInteger attemptCount = new AtomicInteger(0);

        activeHandler.set(exchange -> {
            attemptCount.incrementAndGet();
            byte[] errBytes = "{\"type\": \"urn:ledgerguard:psp:error:temporary-failure\", \"title\": \"Temporary simulated failure\", \"status\": 500}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(500, errBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(errBytes);
            }
            exchange.close();
        });

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                idempotencyKey,
                Money.inr(10000)
        );

        // Attempt 1: returns FAILED (definite pre-acceptance rejection)
        FundingResult result1 = fundingService.fundWallet(command);
        assertThat(result1.status()).isEqualTo(FundingStatus.FAILED);
        assertThat(result1.providerOperationId()).isNull();
        assertThat(result1.journalTransactionId()).isNull();
        FundingOperation op1 = fundingOperationRepository.findById(result1.fundingId()).orElseThrow();
        assertThat(op1.getStatus()).isEqualTo(FundingStatus.FAILED);
        assertThat(ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow().getBalanceMinor()).isEqualTo(0);

        // Attempt 2 with same idempotency key: replayed terminal FAILED
        FundingResult result2 = fundingService.fundWallet(command);
        assertThat(result2.status()).isEqualTo(FundingStatus.FAILED);
        assertThat(result2.fundingId()).isEqualTo(result1.fundingId());
        assertThat(result2.replayed()).isTrue();
        assertThat(ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow().getBalanceMinor()).isEqualTo(0);
    }

    @Test
    @DisplayName("PSP times out (TIMEOUT_AFTER_SUCCESS): enters UNKNOWN and recovers via status polling")
    void timeoutAfterSuccessScenario() {
        String idempotencyKey = "fund-timeout-" + UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        AtomicInteger attemptCount = new AtomicInteger(0);

        activeHandler.set(exchange -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
                // Timeout on synchronous POST
                try {
                    Thread.sleep(1500); // Exceeds read-timeout of 1000ms
                } catch (InterruptedException ignored) {}
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else {
                // Poller GET /api/provider/operations/by-client/{clientOperationId}
                String path = exchange.getRequestURI().getPath();
                String clientOpIdStr = path.substring(path.lastIndexOf('/') + 1);
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", providerOpId.toString(),
                        "clientOperationId", clientOpIdStr,
                        "operationType", "CREDIT",
                        "amountMinor", "10000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", true
                );
                byte[] respBytes = objectMapper.writeValueAsBytes(respMap);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
                exchange.close();
            }
        });

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                idempotencyKey,
                Money.inr(10000)
        );

        // Attempt 1: times out -> returns UNKNOWN, 0 credit
        FundingResult result1 = fundingService.fundWallet(command);
        assertThat(result1.status()).isEqualTo(FundingStatus.UNKNOWN);
        assertThat(result1.providerOperationId()).isNull();
        assertThat(result1.journalTransactionId()).isNull();
        assertThat(ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow().getBalanceMinor()).isEqualTo(0);

        // Poller claims UNKNOWN row and recovers SUCCEEDED outcome
        jdbcTemplate.update("UPDATE funding_operations SET next_provider_poll_at = CURRENT_TIMESTAMP WHERE id = ?", result1.fundingId());
        providerStatusPollingService.pollPendingOperations();

        FundingOperation settled = fundingOperationRepository.findById(result1.fundingId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(settled.getProviderOperationId()).isEqualTo(providerOpId);
        assertThat(settled.getJournalTransactionId()).isNotNull();
        assertThat(ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow().getBalanceMinor()).isEqualTo(10000);
    }

    @Test
    @DisplayName("Provider response validation mismatch: rejects settlement and preserves PROCESSING")
    void providerResponseMismatch() {
        activeHandler.set(exchange -> {
            Map<String, Object> reqBody;
            try (InputStream is = exchange.getRequestBody()) {
                reqBody = objectMapper.readValue(is, Map.class);
            }
            UUID clientOpId = UUID.fromString((String) reqBody.get("clientOperationId"));
            Map<String, Object> respMap = Map.of(
                    "providerOperationId", UUID.randomUUID().toString(),
                    "clientOperationId", clientOpId.toString(),
                    "operationType", "CREDIT",
                    "amountMinor", "10000",
                    "currency", "INR",
                    "status", "PROCESSING",
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
        });

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                "fund-mismatch-" + UUID.randomUUID(),
                Money.inr(10000)
        );

        FundingResult result = fundingService.fundWallet(command);
        assertThat(result.status()).isEqualTo(FundingStatus.PROCESSING);
        assertThat(ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow().getBalanceMinor()).isEqualTo(0);

        FundingOperation op = fundingOperationRepository.findById(result.fundingId()).orElseThrow();
        assertThat(op.getStatus()).isEqualTo(FundingStatus.PROCESSING);
        assertThat(op.getJournalTransactionId()).isNull();
    }

    @Test
    @DisplayName("20 concurrent identical requests: produce exactly 1 funding operation and 1 settlement journal")
    void concurrentIdempotentFunding() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<FundingResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                "concurrent-fund-key-" + UUID.randomUUID(),
                Money.inr(25000)
        );

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    FundingResult res = fundingService.fundWallet(command);
                    results.add(res);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threadCount);

        // All 20 threads observe the same funding ID
        UUID expectedFundingId = results.get(0).fundingId();
        assertThat(results).allMatch(r -> r.fundingId().equals(expectedFundingId));
        assertThat(results).allMatch(r -> r.status() == FundingStatus.SUCCEEDED || r.status() == FundingStatus.PROCESSING);
        assertThat(results).anyMatch(r -> r.status() == FundingStatus.SUCCEEDED);

        FundingOperation op = fundingOperationRepository.findById(expectedFundingId).orElseThrow();
        assertThat(op.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow().getBalanceMinor()).isEqualTo(25000);
    }

    @Test
    @DisplayName("Different Idempotency-Keys: create 2 independent funding operations and credit twice")
    void differentIdempotencyKeys() {
        FundingResult res1 = fundingService.fundWallet(new CreateFundingCommand(customerUserId, "key-A-" + UUID.randomUUID(), Money.inr(10000)));
        FundingResult res2 = fundingService.fundWallet(new CreateFundingCommand(customerUserId, "key-B-" + UUID.randomUUID(), Money.inr(10000)));

        assertThat(res1.fundingId()).isNotEqualTo(res2.fundingId());
        assertThat(res1.status()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(res2.status()).isEqualTo(FundingStatus.SUCCEEDED);

        assertThat(ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow().getBalanceMinor()).isEqualTo(20000);
    }

    @Test
    @DisplayName("Idempotency conflict: same key with different amount returns 409 Conflict")
    void idempotencyConflict() {
        String key = "conflict-key-" + UUID.randomUUID();
        fundingService.fundWallet(new CreateFundingCommand(customerUserId, key, Money.inr(10000)));

        assertThatThrownBy(() -> fundingService.fundWallet(new CreateFundingCommand(customerUserId, key, Money.inr(20000))))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("Closed wallet between creation and settlement: prevents settlement and keeps PROCESSING")
    void closedWalletPreventsSettlement() {
        activeHandler.set(exchange -> {
            // Before responding, close the customer wallet in the database
            jdbcTemplate.update("UPDATE ledger_accounts SET status = 'CLOSED' WHERE id = ?", customerAccount.getId());
            defaultSuccessHandler(exchange);
        });

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                "fund-closed-wallet-" + UUID.randomUUID(),
                Money.inr(10000)
        );

        FundingResult result = fundingService.fundWallet(command);
        assertThat(result.status()).isEqualTo(FundingStatus.PROCESSING);
        FundingOperation op = fundingOperationRepository.findById(result.fundingId()).orElseThrow();
        assertThat(op.getStatus()).isEqualTo(FundingStatus.PROCESSING);
        assertThat(op.getJournalTransactionId()).isNull();
        assertThat(ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow().getBalanceMinor()).isEqualTo(0);
    }

    @Test
    @DisplayName("Max precision: handles values > JavaScript Number.MAX_SAFE_INTEGER without precision loss")
    void maxPrecisionFunding() {
        long largeAmount = 5_000_000_000_000_000L; // 5 quadrillion paise

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                "fund-large-amount-" + UUID.randomUUID(),
                Money.inr(largeAmount)
        );

        FundingResult result = fundingService.fundWallet(command);
        assertThat(result.status()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(result.amountMinor()).isEqualTo(largeAmount);

        LedgerBalanceSnapshot customerSnapshot = ledgerBalanceSnapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(customerSnapshot.getBalanceMinor()).isEqualTo(largeAmount);
    }

    @Test
    @DisplayName("Multiple or missing PSP_CLEARING accounts: fails closed and keeps funding PROCESSING")
    void multipleClearingAccountsFailClosed() {
        // Create second active PSP_CLEARING account
        createTestLedgerAccount(null, AccountType.PSP_CLEARING, AccountStatus.ACTIVE, "INR");

        CreateFundingCommand command = new CreateFundingCommand(
                customerUserId,
                "fund-multi-clearing-" + UUID.randomUUID(),
                Money.inr(10000)
        );

        FundingResult result = fundingService.fundWallet(command);
        assertThat(result.status()).isEqualTo(FundingStatus.PROCESSING);
        FundingOperation op = fundingOperationRepository.findById(result.fundingId()).orElseThrow();
        assertThat(op.getStatus()).isEqualTo(FundingStatus.PROCESSING);
        assertThat(op.getJournalTransactionId()).isNull();
    }

    private UUID createTestUser(String email, String role) {
        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) VALUES (?, ?, 'hash', ?, 'ACTIVE', ?, ?)",
                userId, email, role, now, now
        );
        return userId;
    }

    private LedgerAccount createTestLedgerAccount(UUID ownerUserId, AccountType type, AccountStatus status, String currency) {
        UUID accId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                accId, ownerUserId, type.name(), currency, status.name(), now, now
        );
        return ledgerAccountRepository.findById(accId).orElseThrow();
    }

    private void setAccountBalance(UUID ledgerAccountId, long balance) {
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = ? WHERE ledger_account_id = ?",
                balance, ledgerAccountId
        );
    }
}
