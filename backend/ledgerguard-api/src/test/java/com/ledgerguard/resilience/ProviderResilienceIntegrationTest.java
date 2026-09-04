package com.ledgerguard.resilience;

import tools.jackson.databind.ObjectMapper;
import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.application.*;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.payout.application.CreatePayoutCommand;
import com.ledgerguard.payout.application.PayoutResult;
import com.ledgerguard.payout.application.PayoutService;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.application.ProviderStatusPollingService;
import com.ledgerguard.reconciliation.application.ProviderSettlementChecker;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderResilienceIntegrationTest extends AbstractIntegrationTest {

    private static HttpServer mockPspServer;
    private static int mockPspPort;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static volatile HttpHandler currentCreateHandler;
    private static volatile HttpHandler currentStatusHandler;

    static {
        try {
            mockPspServer = HttpServer.create(new InetSocketAddress(0), 0);
            mockPspPort = mockPspServer.getAddress().getPort();
            mockPspServer.createContext("/api/provider/operations", exchange -> {
                HttpHandler handler = currentCreateHandler;
                if (handler != null) {
                    handler.handle(exchange);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            mockPspServer.createContext("/api/provider/operations/by-client/", exchange -> {
                HttpHandler handler = currentStatusHandler;
                if (handler != null) {
                    handler.handle(exchange);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            mockPspServer.setExecutor(Executors.newCachedThreadPool());
            mockPspServer.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configurePspProperties(DynamicPropertyRegistry registry) {
        registry.add("ledgerguard.psp.base-url", () -> "http://localhost:" + mockPspPort);
        registry.add("ledgerguard.psp.connect-timeout-ms", () -> 2000);
        registry.add("ledgerguard.psp.read-timeout-ms", () -> 300);
        registry.add("ledgerguard.resilience.retry.create.initial-backoff", () -> "50ms");
        registry.add("ledgerguard.resilience.retry.create.max-backoff", () -> "100ms");
        registry.add("ledgerguard.resilience.retry.status.initial-backoff", () -> "50ms");
        registry.add("ledgerguard.resilience.retry.status.max-backoff", () -> "100ms");
    }

    @AfterAll
    static void stopMockServer() {
        if (mockPspServer != null) {
            mockPspServer.stop(0);
        }
    }

    private static void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void respondProblem(HttpExchange exchange, int status, String type, String title) throws IOException {
        String problem = "{\"type\": \"" + type + "\", \"title\": \"" + title + "\", \"status\": " + status + "}";
        byte[] bytes = problem.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Autowired
    private FundingService fundingService;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private FundingOperationRepository fundingRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private BalanceHoldRepository balanceHoldRepository;

    @Autowired
    private ReconciliationItemRepository reconciliationItemRepository;

    @Autowired
    private ProviderSettlementChecker providerSettlementChecker;

    @Autowired
    private ProviderStatusPollingService providerStatusPollingService;

    @Autowired
    private PspClient pspClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID customerAccountId;
    private UUID pspClearingAccountId;

    @BeforeEach
    void setUp() {
        currentCreateHandler = null;
        currentStatusHandler = null;
        Timestamp now = Timestamp.from(Instant.now());
        userId = UUID.randomUUID();
        customerAccountId = UUID.randomUUID();
        pspClearingAccountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                userId, "user-" + userId + "@example.com", now, now
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                customerAccountId, userId, now, now
        );

        jdbcTemplate.update(
                "UPDATE ledger_accounts SET status = 'CLOSED' WHERE account_type = 'PSP_CLEARING' AND currency = 'INR'"
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, NULL, 'PSP_CLEARING', 'INR', 'ACTIVE', ?, ?)",
                pspClearingAccountId, now, now
        );

        // Reset circuit breaker to CLOSED
        pspClient.getCircuitBreaker().reset();
    }

    @Test
    @DisplayName("Pre-network rejection (Circuit OPEN): Funding transitions to FAILED, providerOpId NULL, 0 journals")
    void testFundingPreNetworkCircuitOpenRejection() {
        // Force circuit breaker to OPEN
        pspClient.getCircuitBreaker().transitionToOpenState();

        CreateFundingCommand cmd = new CreateFundingCommand(
                userId,
                "idem-funding-" + UUID.randomUUID(),
                Money.inr(10000)
        );

        FundingResult result = fundingService.fundWallet(cmd);

        assertThat(result.status()).isEqualTo(FundingStatus.FAILED);
        assertThat(result.providerOperationId()).isNull();

        FundingOperation persisted = fundingRepository.findById(result.fundingId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(FundingStatus.FAILED);
        assertThat(persisted.getProviderOperationId()).isNull();
        assertThat(persisted.getJournalTransactionId()).isNull();
        assertThat(persisted.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Pre-network rejection (Circuit OPEN): Payout transitions to FAILED, hold is RELEASED, 0 journals")
    void testPayoutPreNetworkCircuitOpenRejection() {
        setupCustomerBalance(customerAccountId, 50000L);

        // Force circuit breaker to OPEN
        pspClient.getCircuitBreaker().transitionToOpenState();

        CreatePayoutCommand cmd = new CreatePayoutCommand(
                userId,
                "idem-payout-" + UUID.randomUUID(),
                Money.inr(15000)
        );

        PayoutResult result = payoutService.requestPayout(cmd);

        assertThat(result.status()).isEqualTo(PayoutStatus.FAILED);
        assertThat(result.providerOperationId()).isNull();

        Payout persisted = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(persisted.getProviderOperationId()).isNull();
        assertThat(persisted.getJournalTransactionId()).isNull();
        assertThat(persisted.getCompletedAt()).isNotNull();

        // Verify balance hold is RELEASED
        BalanceHold hold = balanceHoldRepository.findById(persisted.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(hold.getTerminalAt()).isNotNull();
    }

    @Test
    @DisplayName("Pre-network rejection (Bulkhead FULL): Payout transitions to FAILED, hold is RELEASED")
    void testPayoutPreNetworkBulkheadFullRejection() {
        setupCustomerBalance(customerAccountId, 50000L);

        // Drain create bulkhead permits
        for (int i = 0; i < 20; i++) {
            pspClient.getCreateBulkhead().tryAcquirePermission();
        }

        CreatePayoutCommand cmd = new CreatePayoutCommand(
                userId,
                "idem-payout-" + UUID.randomUUID(),
                Money.inr(12000)
        );

        try {
            PayoutResult result = payoutService.requestPayout(cmd);
            assertThat(result.status()).isEqualTo(PayoutStatus.FAILED);

            Payout persisted = payoutRepository.findById(result.payoutId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(PayoutStatus.FAILED);

            BalanceHold hold = balanceHoldRepository.findById(persisted.getBalanceHoldId()).orElseThrow();
            assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        } finally {
            // Restore permits
            for (int i = 0; i < 20; i++) {
                pspClient.getCreateBulkhead().onComplete();
            }
        }
    }

    @Test
    @DisplayName("Reconciliation Level 3: when circuit breaker is OPEN, items classified as UNRESOLVED / PROVIDER_UNAVAILABLE")
    void testReconciliationCircuitOpenClassification() {
        Timestamp now = Timestamp.from(Instant.now());
        UUID fundingId = UUID.randomUUID();

        // Insert in CREATED, then transition to PROCESSING under trigger rules
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, 5000, 'INR', 'CREATED', ?)",
                fundingId, userId, customerAccountId, now
        );

        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        );

        // Create an active reconciliation_run record so items can reference it
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', ?)",
                runId, now
        );

        // Force circuit breaker OPEN
        pspClient.getCircuitBreaker().transitionToOpenState();

        providerSettlementChecker.check(runId);

        List<ReconciliationItem> items = reconciliationItemRepository.findByReconciliationRunId(runId, Pageable.unpaged()).getContent();
        assertThat(items).isNotEmpty();

        ReconciliationItem item = items.stream()
                .filter(i -> fundingId.equals(i.getEntityId()))
                .findFirst()
                .orElseThrow();

        assertThat(item.getClassification()).isEqualTo(ReconciliationClassification.UNRESOLVED);
        assertThat(item.getProblemType()).isEqualTo(ReconciliationProblemType.PROVIDER_UNAVAILABLE);
        assertThat(item.getDescription()).contains("Provider call rejected (CIRCUIT_OPEN)");

        // Verify entity state remained PROCESSING (detection only, zero mutation)
        FundingOperation funding = fundingRepository.findById(fundingId).orElseThrow();
        assertThat(funding.getStatus()).isEqualTo(FundingStatus.PROCESSING);

        // Also verify Bulkhead Full under reconciliation persists UNRESOLVED / PROVIDER_UNAVAILABLE
        pspClient.getCircuitBreaker().transitionToClosedState();
        for (int i = 0; i < 20; i++) {
            pspClient.getStatusBulkhead().tryAcquirePermission();
        }
        try {
            UUID runId2 = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', ?)",
                    runId2, now
            );
            providerSettlementChecker.check(runId2);
            List<ReconciliationItem> items2 = reconciliationItemRepository.findByReconciliationRunId(runId2, Pageable.unpaged()).getContent();
            ReconciliationItem bhItem = items2.stream()
                    .filter(i -> fundingId.equals(i.getEntityId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(bhItem.getClassification()).isEqualTo(ReconciliationClassification.UNRESOLVED);
            assertThat(bhItem.getProblemType()).isEqualTo(ReconciliationProblemType.PROVIDER_UNAVAILABLE);
            assertThat(bhItem.getDescription()).contains("Provider call rejected (BULKHEAD_FULL)");
        } finally {
            for (int i = 0; i < 20; i++) {
                pspClient.getStatusBulkhead().onComplete();
            }
        }
    }

    @Test
    @DisplayName("Funding TIMEOUT_AFTER_SUCCESS E2E: physical attempt 1 times out after commit, retry receives authoritative SUCCEEDED")
    void testFundingTimeoutAfterSuccessReplaySettlesSucceeded() {
        AtomicInteger physicalAttempts = new AtomicInteger(0);
        Map<UUID, Map<String, Object>> providerStore = new ConcurrentHashMap<>();

        currentCreateHandler = exchange -> {
            int attempt = physicalAttempts.incrementAndGet();
            System.err.println("DEBUG CREATE HANDLER: attempt=" + attempt);
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                if (attempt == 1) {
                    // Provider commits operation durably before delay
                    UUID providerOpId = UUID.randomUUID();
                    Map<String, Object> op = new HashMap<>();
                    op.put("providerOperationId", providerOpId.toString());
                    op.put("clientOperationId", clientOpId.toString());
                    op.put("operationType", "CREDIT");
                    op.put("amountMinor", "10000");
                    op.put("currency", "INR");
                    op.put("status", "SUCCEEDED");
                    op.put("createdAt", Instant.now().toString());
                    op.put("completedAt", Instant.now().toString());
                    providerStore.put(clientOpId, op);

                    // Delay response past 2000ms read timeout
                    Thread.sleep(2500);
                    respondJson(exchange, 201, op);
                } else {
                    // Retry: returns authoritative existing operation
                    Map<String, Object> op = providerStore.get(clientOpId);
                    Map<String, Object> resp = new HashMap<>(op);
                    resp.put("replayed", true);
                    respondJson(exchange, 200, resp);
                }
            } catch (Exception ex) {
                System.err.println("DEBUG CREATE HANDLER EXCEPTION: " + ex);
                ex.printStackTrace();
            }
        };

        CreateFundingCommand cmd = new CreateFundingCommand(
                userId,
                "funding-timeout-" + UUID.randomUUID(),
                Money.inr(10000)
        );

        FundingResult result = fundingService.fundWallet(cmd);

        assertThat(physicalAttempts.get()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(result.providerOperationId()).isNotNull();

        // 1. LedgerGuard funding row count = 1
        Integer fundingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM funding_operations WHERE id = ?", Integer.class, result.fundingId());
        assertThat(fundingCount).isEqualTo(1);

        FundingOperation funding = fundingRepository.findById(result.fundingId()).orElseThrow();
        assertThat(funding.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(funding.getProviderOperationId()).isNotNull();

        // 2. Provider store contains exactly 1 row for clientOperationId
        assertThat(providerStore).hasSize(1);
        Map<String, Object> providerOp = providerStore.get(funding.getId());
        assertThat(providerOp).isNotNull();

        // 3. providerOperationId returned on replay = providerOperationId stored locally
        assertThat(result.providerOperationId().toString()).isEqualTo(providerOp.get("providerOperationId"));
        assertThat(funding.getProviderOperationId().toString()).isEqualTo(providerOp.get("providerOperationId"));

        // 4. Settlement journal transactions for the funding effect = exactly 1
        UUID journalTxnId = funding.getJournalTransactionId();
        assertThat(journalTxnId).isNotNull();
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_transactions WHERE id = ?", Integer.class, journalTxnId);
        assertThat(journalCount).isEqualTo(1);

        // 5. Settlement journal entries = exactly 2 (one DEBIT, one CREDIT, amounts equal funding amount)
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT direction, amount_minor, ledger_account_id FROM journal_entries WHERE journal_transaction_id = ?", journalTxnId);
        assertThat(entries).hasSize(2);
        long debits = entries.stream().filter(e -> "DEBIT".equals(e.get("direction"))).mapToLong(e -> (Long) e.get("amount_minor")).sum();
        long credits = entries.stream().filter(e -> "CREDIT".equals(e.get("direction"))).mapToLong(e -> (Long) e.get("amount_minor")).sum();
        assertThat(debits).isEqualTo(10000L);
        assertThat(credits).isEqualTo(10000L);

        // 6. Customer balance changes exactly once (0 -> 10000)
        Long customerBal = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, customerAccountId);
        assertThat(customerBal).isEqualTo(10000L);

        // No UNKNOWN final state
        assertThat(funding.getUnknownSince()).isNull();
    }

    @Test
    @DisplayName("Payout TIMEOUT_AFTER_SUCCESS E2E: physical attempt 1 times out after commit, retry receives authoritative SUCCEEDED, hold is CONSUMED")
    void testPayoutTimeoutAfterSuccessReplaySettlesSucceeded() {
        setupCustomerBalance(customerAccountId, 50000L);

        AtomicInteger physicalAttempts = new AtomicInteger(0);
        Map<UUID, Map<String, Object>> providerStore = new ConcurrentHashMap<>();

        currentCreateHandler = exchange -> {
            int attempt = physicalAttempts.incrementAndGet();
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                if (attempt == 1) {
                    UUID providerOpId = UUID.randomUUID();
                    Map<String, Object> op = new HashMap<>();
                    op.put("providerOperationId", providerOpId.toString());
                    op.put("clientOperationId", clientOpId.toString());
                    op.put("operationType", "DEBIT");
                    op.put("amountMinor", "15000");
                    op.put("currency", "INR");
                    op.put("status", "SUCCEEDED");
                    op.put("createdAt", Instant.now().toString());
                    op.put("completedAt", Instant.now().toString());
                    providerStore.put(clientOpId, op);

                    // Delay response past 2000ms read timeout
                    Thread.sleep(2500);
                    respondJson(exchange, 201, op);
                } else {
                    Map<String, Object> op = providerStore.get(clientOpId);
                    Map<String, Object> resp = new HashMap<>(op);
                    resp.put("replayed", true);
                    respondJson(exchange, 200, resp);
                }
            } catch (Exception ignored) {}
        };

        CreatePayoutCommand cmd = new CreatePayoutCommand(
                userId,
                "payout-timeout-" + UUID.randomUUID(),
                Money.inr(15000)
        );

        PayoutResult result = payoutService.requestPayout(cmd);

        assertThat(physicalAttempts.get()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(PayoutStatus.SUCCEEDED);

        // 1. Payout row count = 1
        Integer payoutCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payouts WHERE id = ?", Integer.class, result.payoutId());
        assertThat(payoutCount).isEqualTo(1);

        Payout payout = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);

        // 2. providerOperationId stable
        assertThat(providerStore).hasSize(1);
        Map<String, Object> providerOp = providerStore.get(payout.getId());
        assertThat(providerOp).isNotNull();
        assertThat(result.providerOperationId().toString()).isEqualTo(providerOp.get("providerOperationId"));
        assertThat(payout.getProviderOperationId().toString()).isEqualTo(providerOp.get("providerOperationId"));

        // 3. Settlement journal = exactly 1, entries = exactly 2
        UUID journalTxnId = payout.getJournalTransactionId();
        assertThat(journalTxnId).isNotNull();
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_transactions WHERE id = ?", Integer.class, journalTxnId);
        assertThat(journalCount).isEqualTo(1);

        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT direction, amount_minor, ledger_account_id FROM journal_entries WHERE journal_transaction_id = ?", journalTxnId);
        assertThat(entries).hasSize(2);
        long debits = entries.stream().filter(e -> "DEBIT".equals(e.get("direction"))).mapToLong(e -> (Long) e.get("amount_minor")).sum();
        long credits = entries.stream().filter(e -> "CREDIT".equals(e.get("direction"))).mapToLong(e -> (Long) e.get("amount_minor")).sum();
        assertThat(debits).isEqualTo(15000L);
        assertThat(credits).isEqualTo(15000L);

        // Source wallet debited once (50000 - 15000 = 35000)
        Long customerBal = jdbcTemplate.queryForObject(
                "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", Long.class, customerAccountId);
        assertThat(customerBal).isEqualTo(35000L);

        // 4. Linked BalanceHold: ACTIVE -> CONSUMED exactly once (not RELEASED, not left ACTIVE)
        BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONSUMED);
        assertThat(hold.getTerminalAt()).isNotNull();

        // No UNKNOWN final state
        assertThat(payout.getUnknownSince()).isNull();
    }

    @Test
    @DisplayName("Mixed ambiguity: attempt 1 timeout, attempts 2 & 3 temporary 500 -> Funding & Payout UNKNOWN, hold ACTIVE")
    void testMixedAmbiguityFundingAndPayoutRemainUnknown() {
        // --- FUNDING ---
        AtomicInteger fundingAttempts = new AtomicInteger(0);
        currentCreateHandler = exchange -> {
            int att = fundingAttempts.incrementAndGet();
            if (att == 1) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {}
            } else {
                respondProblem(exchange, 500, "urn:ledgerguard:psp:error:temporary-failure", "Temporary simulated failure");
            }
        };

        CreateFundingCommand fundCmd = new CreateFundingCommand(
                userId,
                "funding-mixed-" + UUID.randomUUID(),
                Money.inr(8000)
        );
        FundingResult fundResult = fundingService.fundWallet(fundCmd);

        assertThat(fundingAttempts.get()).isEqualTo(3);
        assertThat(fundResult.status()).isEqualTo(FundingStatus.UNKNOWN);
        assertThat(fundResult.providerOperationId()).isNull();

        FundingOperation fundOp = fundingRepository.findById(fundResult.fundingId()).orElseThrow();
        assertThat(fundOp.getStatus()).isEqualTo(FundingStatus.UNKNOWN);
        assertThat(fundOp.getUnknownSince()).isNotNull();
        assertThat(fundOp.getJournalTransactionId()).isNull();

        // --- PAYOUT ---
        setupCustomerBalance(customerAccountId, 50000L);
        AtomicInteger payoutAttempts = new AtomicInteger(0);
        currentCreateHandler = exchange -> {
            int att = payoutAttempts.incrementAndGet();
            if (att == 1) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {}
            } else {
                respondProblem(exchange, 500, "urn:ledgerguard:psp:error:temporary-failure", "Temporary simulated failure");
            }
        };

        CreatePayoutCommand payoutCmd = new CreatePayoutCommand(
                userId,
                "payout-mixed-" + UUID.randomUUID(),
                Money.inr(12000)
        );
        PayoutResult payoutResult = payoutService.requestPayout(payoutCmd);

        assertThat(payoutAttempts.get()).isEqualTo(3);
        assertThat(payoutResult.status()).isEqualTo(PayoutStatus.UNKNOWN);
        assertThat(payoutResult.providerOperationId()).isNull();

        Payout payout = payoutRepository.findById(payoutResult.payoutId()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.UNKNOWN);
        assertThat(payout.getUnknownSince()).isNotNull();
        assertThat(payout.getJournalTransactionId()).isNull();

        // Hold MUST remain ACTIVE (never released, never consumed)
        BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getTerminalAt()).isNull();
    }

    @Test
    @DisplayName("Poll counter retry test: 3 physical GET retries do NOT inflate durable provider_poll_attempts (N -> N+1, NOT N+3)")
    void testPollCounterRetryDoesNotIncrementDurableAttempts() {
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(30));
        UUID fundingId = UUID.randomUUID();
        UUID payoutId = UUID.randomUUID();

        // 1. Funding row: CREATED -> PROCESSING -> UNKNOWN with provider_poll_attempts = 1
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, 5000, 'INR', 'CREATED', ?)",
                fundingId, userId, customerAccountId, past
        );
        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                past, fundingId
        );
        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'UNKNOWN', unknown_since = ?, next_provider_poll_at = ?, provider_poll_attempts = 1 WHERE id = ?",
                past, past, fundingId
        );

        // 2. Payout row: CREATED -> PROCESSING -> UNKNOWN with provider_poll_attempts = 2
        setupCustomerBalance(customerAccountId, 50000L);
        UUID holdId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, created_at, updated_at, expires_at) " +
                        "VALUES (?, ?, 5000, 'INR', 'ACTIVE', ?, ?, ?)",
                holdId, customerAccountId, past, past, Timestamp.from(Instant.now().plusSeconds(300))
        );
        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 5000, 'INR', 'CREATED', ?)",
                payoutId, userId, customerAccountId, holdId, past
        );
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                past, payoutId
        );
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'UNKNOWN', unknown_since = ?, next_provider_poll_at = ?, provider_poll_attempts = 2 WHERE id = ?",
                past, past, payoutId
        );

        AtomicInteger physicalGets = new AtomicInteger(0);
        currentStatusHandler = exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains(fundingId.toString()) || path.contains(payoutId.toString())) {
                physicalGets.incrementAndGet();
            }
            respondProblem(exchange, 500, "urn:ledgerguard:psp:error:temporary-failure", "Temporary simulated outage");
        };

        providerStatusPollingService.pollPendingOperations();

        // 3 physical GETs dispatched per entity (total 6 across funding and payout)
        assertThat(physicalGets.get()).isEqualTo(6);

        // Check funding durable counter in DB: was 1, claimed once -> must be 2 (NOT 1 + 3 = 4!)
        Integer fundingAttempts = jdbcTemplate.queryForObject(
                "SELECT provider_poll_attempts FROM funding_operations WHERE id = ?", Integer.class, fundingId);
        assertThat(fundingAttempts).isEqualTo(2);

        // Check payout durable counter in DB: was 2, claimed once -> must be 3 (NOT 2 + 3 = 5!)
        Integer payoutAttempts = jdbcTemplate.queryForObject(
                "SELECT provider_poll_attempts FROM payouts WHERE id = ?", Integer.class, payoutId);
        assertThat(payoutAttempts).isEqualTo(3);
    }

    @Test
    @DisplayName("Status bulkhead saturation does NOT starve independent CREATE calls")
    void testStatusBulkheadSaturationDoesNotStarveCreate() {
        // Saturate all 20 permits of psp-status
        int maxPermits = 20;
        for (int i = 0; i < maxPermits; i++) {
            boolean acquired = pspClient.getStatusBulkhead().tryAcquirePermission();
            assertThat(acquired).isTrue();
        }

        try {
            AtomicInteger createDispatched = new AtomicInteger(0);
            currentCreateHandler = exchange -> {
                createDispatched.incrementAndGet();
                try (InputStream is = exchange.getRequestBody()) {
                    Map<?, ?> body = objectMapper.readValue(is, Map.class);
                    UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                    Map<String, Object> respMap = Map.of(
                            "providerOperationId", UUID.randomUUID().toString(),
                            "clientOperationId", clientOpId.toString(),
                            "operationType", "CREDIT",
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

            CreateFundingCommand cmd = new CreateFundingCommand(
                    userId,
                    "funding-bulkhead-iso-" + UUID.randomUUID(),
                    Money.inr(10000)
            );

            FundingResult result = fundingService.fundWallet(cmd);

            // CREATE reached raw PSP HTTP, succeeded, and was NOT starved by full status bulkhead
            assertThat(createDispatched.get()).isEqualTo(1);
            assertThat(result.status()).isEqualTo(FundingStatus.SUCCEEDED);
            assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        } finally {
            // Restore status permits
            for (int i = 0; i < maxPermits; i++) {
                pspClient.getStatusBulkhead().onComplete();
            }
        }
    }

    @Test
    @DisplayName("Circuit half-open recovery: CLOSED -> OPEN -> fast fail (0 raw HTTP) -> HALF_OPEN -> CLOSED on successful probe")
    void testCircuitHalfOpenRecovery() {
        AtomicInteger rawHttpCount = new AtomicInteger(0);
        currentCreateHandler = exchange -> {
            rawHttpCount.incrementAndGet();
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "CREDIT",
                        "amountMinor", "5000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        // 1. CLOSED -> OPEN
        pspClient.getCircuitBreaker().transitionToOpenState();
        assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 2. While OPEN: provider call fails fast, raw HTTP attempts = 0
        CreateFundingCommand cmd1 = new CreateFundingCommand(
                userId,
                "funding-cb-open-" + UUID.randomUUID(),
                Money.inr(5000)
        );
        FundingResult res1 = fundingService.fundWallet(cmd1);
        assertThat(res1.status()).isEqualTo(FundingStatus.FAILED);
        assertThat(rawHttpCount.get()).isEqualTo(0);

        // 3. OPEN -> HALF_OPEN
        pspClient.getCircuitBreaker().transitionToHalfOpenState();
        assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 4. Successful probe(s) in HALF_OPEN -> transitions to CLOSED
        for (int i = 0; i < 5; i++) {
            PspOperationResponse probeResp = pspClient.createOperation(UUID.randomUUID(), "CREDIT", "5000", "INR");
            assertThat(probeResp).isNotNull();
        }

        // Circuit breaker returned to CLOSED!
        assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private void setupCustomerBalance(UUID accountId, long amountMinor) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID journalTxId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) VALUES (?, 'DRAFT', 'INR', ?, NULL)",
                journalTxId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'DEBIT', ?)",
                UUID.randomUUID(), journalTxId, pspClearingAccountId, amountMinor
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'CREDIT', ?)",
                UUID.randomUUID(), journalTxId, accountId, amountMinor
        );
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalTxId
        );
    }
}
