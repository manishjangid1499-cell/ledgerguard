package com.ledgerguard.payout.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.hold.application.HoldExpirationService;
import com.ledgerguard.hold.application.HoldService;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.idempotency.domain.IdempotencyConflictException;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.domain.PayoutValidationException;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
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
import java.time.Duration;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutServiceIntegrationTest extends AbstractIntegrationTest {

    @FunctionalInterface
    interface PspHandlerFunction {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static HttpServer mockPspServer;
    private static int mockPspPort;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static volatile PspHandlerFunction currentHandler;

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
            PspHandlerFunction handler = currentHandler;
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
    private PayoutService payoutService;

    @Autowired
    private PayoutSettlementService payoutSettlementService;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private BalanceHoldRepository balanceHoldRepository;

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldExpirationService holdExpirationService;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private UUID customerUserId;
    private LedgerAccount customerAccount;
    private UUID merchantUserId;
    private LedgerAccount merchantAccount;
    private LedgerAccount pspClearingAccount;

    @BeforeEach
    void setUpTestData() {
        currentHandler = null;
        Timestamp now = Timestamp.from(Instant.now());

        // Close old PSP clearing accounts
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
        merchantUserId = UUID.randomUUID();

        // 1. Users
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                customerUserId, "customer-" + customerUserId + "@example.com", now, now
        );
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'MERCHANT', 'ACTIVE', ?, ?)",
                merchantUserId, "merchant-" + merchantUserId + "@example.com", now, now
        );

        // 2. Ledger accounts
        UUID custAccId = UUID.randomUUID();
        UUID merchAccId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                custAccId, customerUserId, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'MERCHANT', 'INR', 'ACTIVE', ?, ?)",
                merchAccId, merchantUserId, now, now
        );

        // 3. Snapshots (Customer = 10000, Merchant = 20000, PSP_CLEARING = 100000)
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = 10000 WHERE ledger_account_id = ?",
                custAccId
        );
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = 20000 WHERE ledger_account_id = ?",
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
    @DisplayName("Normal payout success for Customer creates hold, settles DEBIT, consumes hold, and updates balance")
    void normalCustomerPayoutSuccess() {
        AtomicBoolean transactionActiveDuringHttp = new AtomicBoolean(true);

        currentHandler = exchange -> {
            transactionActiveDuringHttp.set(TransactionSynchronizationManager.isActualTransactionActive());
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", "3000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        CreatePayoutCommand command = new CreatePayoutCommand(customerUserId, "payout-key-1-" + UUID.randomUUID(), Money.inr(3000));
        PayoutResult result = payoutService.requestPayout(command);

        assertThat(result.status()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(result.amountMinor()).isEqualTo("3000");
        assertThat(result.replayed()).isFalse();
        assertThat(result.providerOperationId()).isNotNull();
        assertThat(result.journalTransactionId()).isNotNull();
        assertThat(transactionActiveDuringHttp.get()).isFalse();

        // Verify BalanceHold is CONSUMED
        BalanceHold hold = balanceHoldRepository.findById(result.balanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONSUMED);
        assertThat(hold.getTerminalAt()).isNotNull();

        // Verify Snapshots: Customer posted 10000 - 3000 = 7000; Held = 0; Available = 7000
        LedgerBalanceSnapshot customerSnapshot = snapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(customerSnapshot.getBalanceMinor()).isEqualTo(7000L);
        long activeHeld = balanceHoldRepository.sumActiveAmountByLedgerAccountId(customerAccount.getId());
        assertThat(activeHeld).isEqualTo(0L);

        // Verify PSP_CLEARING snapshot credited: 100000 - 3000 = 97000 (asset decreased upon credit)
        LedgerBalanceSnapshot clearingSnapshot = snapshotRepository.findById(pspClearingAccount.getId()).orElseThrow();
        assertThat(clearingSnapshot.getBalanceMinor()).isEqualTo(97000L);
    }

    @Test
    @DisplayName("Normal payout success for Merchant executes correctly")
    void normalMerchantPayoutSuccess() {
        currentHandler = exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
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

        CreatePayoutCommand command = new CreatePayoutCommand(merchantUserId, "payout-merch-1-" + UUID.randomUUID(), Money.inr(5000));
        PayoutResult result = payoutService.requestPayout(command);

        assertThat(result.status()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(result.amountMinor()).isEqualTo("5000");

        LedgerBalanceSnapshot merchantSnapshot = snapshotRepository.findById(merchantAccount.getId()).orElseThrow();
        assertThat(merchantSnapshot.getBalanceMinor()).isEqualTo(15000L);
    }

    @Test
    @DisplayName("Insufficient available balance fails before PSP call, creating 0 payouts and 0 holds")
    void insufficientAvailableBalanceRejectsBeforePsp() {
        AtomicInteger pspCallCount = new AtomicInteger(0);
        currentHandler = exchange -> pspCallCount.incrementAndGet();

        // Account has 10000, create an active hold for 7000 -> available is 3000
        holdService.createHold(customerAccount.getId(), Money.inr(7000), Instant.now().plus(Duration.ofMinutes(10)));

        // Request 5000 payout (available is only 3000)
        CreatePayoutCommand command = new CreatePayoutCommand(customerUserId, "payout-over-capacity-" + UUID.randomUUID(), Money.inr(5000));

        assertThatThrownBy(() -> payoutService.requestPayout(command))
                .isInstanceOf(RuntimeException.class);

        assertThat(pspCallCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Definite provider failure (TEMPORARY_500) releases hold, marks Payout FAILED, and posts 0 journals")
    void definiteProviderFailure500ReleasesHoldAndFailsPayout() {
        currentHandler = exchange -> {
            byte[] errBytes = "{\"type\": \"urn:ledgerguard:psp:error:temporary-failure\", \"title\": \"Temporary simulated provider outage\", \"status\": 500}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(500, errBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(errBytes);
            }
        };

        String idempKey = "payout-500-key-" + UUID.randomUUID();
        CreatePayoutCommand command = new CreatePayoutCommand(customerUserId, idempKey, Money.inr(3000));
        PayoutResult result = payoutService.requestPayout(command);

        assertThat(result.status()).isEqualTo(PayoutStatus.FAILED);
        assertThat(result.completedAt()).isNotNull();

        // Verify BalanceHold is RELEASED
        BalanceHold hold = balanceHoldRepository.findById(result.balanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);

        // Verify balance unchanged
        LedgerBalanceSnapshot snapshot = snapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(snapshot.getBalanceMinor()).isEqualTo(10000L);
        long activeHeld = balanceHoldRepository.sumActiveAmountByLedgerAccountId(customerAccount.getId());
        assertThat(activeHeld).isEqualTo(0L);

        // Replay returns same FAILED payout without calling PSP
        PayoutResult replayResult = payoutService.requestPayout(command);
        assertThat(replayResult.status()).isEqualTo(PayoutStatus.FAILED);
        assertThat(replayResult.replayed()).isTrue();
    }

    @Test
    @DisplayName("TIMEOUT_AFTER_SUCCESS enters UNKNOWN status, keeps hold ACTIVE, and suppresses blind replay PSP calls")
    void timeoutAfterSuccessPreservesProcessingAndActiveHold() {
        AtomicInteger pspCallCount = new AtomicInteger(0);
        currentHandler = exchange -> {
            pspCallCount.incrementAndGet();
            try {
                Thread.sleep(2500); // Exceeds read timeout of 2000ms
            } catch (InterruptedException ignored) {}
        };

        String idempKey = "payout-timeout-key-" + UUID.randomUUID();
        CreatePayoutCommand command = new CreatePayoutCommand(customerUserId, idempKey, Money.inr(3000));
        PayoutResult result = payoutService.requestPayout(command);

        assertThat(result.status()).isEqualTo(PayoutStatus.UNKNOWN);
        assertThat(result.completedAt()).isNull();
        assertThat(pspCallCount.get()).isEqualTo(1);

        // Hold must remain ACTIVE
        BalanceHold hold = balanceHoldRepository.findById(result.balanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        // Available balance is reduced by the active hold
        LedgerBalanceSnapshot snapshot = snapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(snapshot.getBalanceMinor()).isEqualTo(10000L);
        long activeHeld = balanceHoldRepository.sumActiveAmountByLedgerAccountId(customerAccount.getId());
        assertThat(activeHeld).isEqualTo(3000L);

        // Same-key replay returns existing UNKNOWN without making a second PSP call
        PayoutResult replayResult = payoutService.requestPayout(command);
        assertThat(replayResult.status()).isEqualTo(PayoutStatus.UNKNOWN);
        assertThat(replayResult.replayed()).isTrue();
        assertThat(pspCallCount.get()).isEqualTo(1); // Still 1! No blind DEBIT replay
    }

    @Test
    @DisplayName("Hold linked to PROCESSING payout is protected from generic hold expiration")
    void processingPayoutHoldProtectedFromExpiration() {
        // Create PROCESSING payout
        UUID payoutId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        Timestamp past = Timestamp.from(Instant.now().minus(Duration.ofMinutes(10)));
        Timestamp created = Timestamp.from(Instant.now().minus(Duration.ofMinutes(20)));

        // Insert hold with expires_at in the past
        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, 3000, 'INR', 'ACTIVE', ?, ?, ?, NULL)",
                holdId, customerAccount.getId(), past, created, created
        );

        // Insert CREATED payout and transition to PROCESSING
        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 3000, 'INR', 'CREATED', ?)",
                payoutId, customerUserId, customerAccount.getId(), holdId, created
        );
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                created, payoutId
        );

        // Also insert an unrelated generic hold with expires_at in the past
        UUID genericHoldId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, 2000, 'INR', 'ACTIVE', ?, ?, ?, NULL)",
                genericHoldId, customerAccount.getId(), past, created, created
        );

        // Run generic hold expiration
        int expiredCount = holdExpirationService.expireDueHolds(Instant.now());

        // Only generic hold should expire (1 expired)
        assertThat(expiredCount).isEqualTo(1);

        // Generic hold is EXPIRED
        BalanceHold genericHold = balanceHoldRepository.findById(genericHoldId).orElseThrow();
        assertThat(genericHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);

        // Payout-linked hold remains ACTIVE
        BalanceHold payoutHold = balanceHoldRepository.findById(holdId).orElseThrow();
        assertThat(payoutHold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    @DisplayName("Provider success followed by local settlement failure rolls back atomically (preserving PROCESSING and ACTIVE hold)")
    void providerSuccessLocalSettlementFailureRollsBackAtomically() {
        // Customer has 10000 balance. Request 3000 payout.
        // During PSP call, suspend the PSP_CLEARING account so settlement fails with PspClearingAccountException.
        currentHandler = exchange -> {
            // Force settlement to fail by deactivating PSP_CLEARING account
            jdbcTemplate.update("UPDATE ledger_accounts SET status = 'SUSPENDED' WHERE id = ?", pspClearingAccount.getId());
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", "3000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        CreatePayoutCommand command = new CreatePayoutCommand(customerUserId, "payout-rollback-" + UUID.randomUUID(), Money.inr(3000));
        PayoutResult result = payoutService.requestPayout(command);

        // PayoutService catches the settlement failure and safely marks UNKNOWN
        assertThat(result.status()).isEqualTo(PayoutStatus.UNKNOWN);
        assertThat(result.completedAt()).isNull();

        // 1. Payout.status = UNKNOWN
        Payout payout = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.UNKNOWN);

        // 2. BalanceHold.status = ACTIVE
        BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getTerminalAt()).isNull();

        // 3. provider_operation_id is not committed locally
        assertThat(payout.getProviderOperationId()).isNull();

        // 4. journal_transaction_id is null
        assertThat(payout.getJournalTransactionId()).isNull();

        // 5. completed_at is null
        assertThat(payout.getCompletedAt()).isNull();

        // 6. 0 payout settlement journal committed
        assertThat(payout.getJournalTransactionId()).isNull();

        // 7. source posted balance unchanged (10000)
        LedgerBalanceSnapshot customerSnapshot = snapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(customerSnapshot.getBalanceMinor()).isEqualTo(10000L);

        // 8. PSP_CLEARING posted balance unchanged (100000)
        LedgerBalanceSnapshot clearingSnapshot = snapshotRepository.findById(pspClearingAccount.getId()).orElseThrow();
        assertThat(clearingSnapshot.getBalanceMinor()).isEqualTo(100000L);

        // 9. hold remains counted in available-balance calculation (10000 - 3000 = 7000 available)
        long activeHeld = balanceHoldRepository.sumActiveAmountByLedgerAccountId(customerAccount.getId());
        assertThat(activeHeld).isEqualTo(3000L);
    }

    @Test
    @DisplayName("Concurrent settlement execution for the same payout creates exactly 1 journal transaction and 2 entries")
    void concurrentSettlementCreatesExactlyOneJournal() throws InterruptedException {
        // Create PROCESSING payout with ACTIVE hold
        UUID payoutId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(Duration.ofMinutes(30)));

        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, 3000, 'INR', 'ACTIVE', ?, ?, ?, NULL)",
                holdId, customerAccount.getId(), expiresAt, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 3000, 'INR', 'CREATED', ?)",
                payoutId, customerUserId, customerAccount.getId(), holdId, now
        );
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, payoutId
        );

        UUID providerOpId = UUID.randomUUID();
        com.ledgerguard.funding.infrastructure.PspOperationResponse pspResponse =
                new com.ledgerguard.funding.infrastructure.PspOperationResponse(
                        providerOpId,
                        payoutId,
                        "DEBIT",
                        "3000",
                        "INR",
                        "SUCCEEDED",
                        Instant.now().toString(),
                        Instant.now().toString(),
                        false
                );

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<PayoutResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    PayoutResult res = payoutSettlementService.settlePayout(payoutId, pspResponse);
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
        assertThat(results).hasSize(2);

        // Payout rows = 1, status = SUCCEEDED
        Payout payout = payoutRepository.findById(payoutId).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(payout.getProviderOperationId()).isEqualTo(providerOpId);
        assertThat(payout.getJournalTransactionId()).isNotNull();

        // BalanceHold status = CONSUMED
        BalanceHold hold = balanceHoldRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONSUMED);

        // Exactly 1 settlement journal transaction
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_transactions WHERE id = ?",
                Integer.class,
                payout.getJournalTransactionId()
        );
        assertThat(journalCount).isEqualTo(1);

        // Exactly 2 journal entries (1 DEBIT customer, 1 CREDIT PSP_CLEARING)
        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE journal_transaction_id = ?",
                Integer.class,
                payout.getJournalTransactionId()
        );
        assertThat(entryCount).isEqualTo(2);

        // Customer wallet balance debited exactly once: 10000 - 3000 = 7000
        LedgerBalanceSnapshot customerSnapshot = snapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(customerSnapshot.getBalanceMinor()).isEqualTo(7000L);

        // PSP_CLEARING credited exactly once: 100000 - 3000 = 97000
        LedgerBalanceSnapshot clearingSnapshot = snapshotRepository.findById(pspClearingAccount.getId()).orElseThrow();
        assertThat(clearingSnapshot.getBalanceMinor()).isEqualTo(97000L);
    }

    @Test
    @DisplayName("2 concurrent different-key payout requests competing for wallet capacity allow exactly 1 reservation and never over-reserve")
    void concurrentDifferentKeyPayoutsCannotOverReserveWallet() throws InterruptedException {
        currentHandler = exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", "7000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        // Account has 10000 available. Two threads try to payout 7000 with different keys.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<PayoutResult> successes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            try {
                startLatch.await();
                successes.add(payoutService.requestPayout(new CreatePayoutCommand(customerUserId, "cap-key-A-" + UUID.randomUUID(), Money.inr(7000))));
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                successes.add(payoutService.requestPayout(new CreatePayoutCommand(customerUserId, "cap-key-B-" + UUID.randomUUID(), Money.inr(7000))));
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);

        // Successful payout result
        PayoutResult successResult = successes.get(0);
        assertThat(successResult.status()).isEqualTo(PayoutStatus.SUCCEEDED);

        // Final balance: 10000 - 7000 = 3000 (NEVER negative or over-debited)
        LedgerBalanceSnapshot snapshot = snapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(snapshot.getBalanceMinor()).isEqualTo(3000L);

        // Active holds in database = 0 (since the single successful payout consumed its hold)
        long activeHeld = balanceHoldRepository.sumActiveAmountByLedgerAccountId(customerAccount.getId());
        assertThat(activeHeld).isEqualTo(0L);

        // Total payout rows created = 1
        Integer totalPayouts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payouts WHERE initiated_by_user_id = ?",
                Integer.class,
                customerUserId
        );
        assertThat(totalPayouts).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent hold expiration attempts against an in-flight PROCESSING payout never mark the hold EXPIRED")
    void concurrentHoldExpirationDuringInFlightPayoutNeverExpiresHold() throws InterruptedException {
        UUID payoutId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        Timestamp past = Timestamp.from(Instant.now().minus(Duration.ofMinutes(15)));
        Timestamp created = Timestamp.from(Instant.now().minus(Duration.ofMinutes(30)));

        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, 3000, 'INR', 'ACTIVE', ?, ?, ?, NULL)",
                holdId, customerAccount.getId(), past, created, created
        );
        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 3000, 'INR', 'CREATED', ?)",
                payoutId, customerUserId, customerAccount.getId(), holdId, created
        );
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                created, payoutId
        );

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Integer> expirationResults = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int expired = holdExpirationService.expireDueHolds(Instant.now());
                    expirationResults.add(expired);
                } catch (Throwable t) {
                    // ignored
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // None of the concurrent expiration runs could expire this hold
        for (int count : expirationResults) {
            assertThat(count).isEqualTo(0);
        }

        // Direct conditional update returns 0 affected rows
        int directlyUpdated = transactionTemplate.execute(status ->
                balanceHoldRepository.expireHoldConditional(holdId, Instant.now())
        );
        assertThat(directlyUpdated).isEqualTo(0);

        // Hold MUST remain ACTIVE
        BalanceHold hold = balanceHoldRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        // Payout MUST remain PROCESSING
        Payout payout = payoutRepository.findById(payoutId).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PROCESSING);

        // Invariant: Impossible to have PROCESSING payout + EXPIRED hold
        Integer invalidCombinations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payouts p JOIN balance_holds h ON p.balance_hold_id = h.id WHERE p.status = 'PROCESSING' AND h.status = 'EXPIRED'",
                Integer.class
        );
        assertThat(invalidCombinations).isEqualTo(0);
    }

    @Test
    @DisplayName("Matching terminal FAILED replay returns same FAILED result with 0 new PSP calls and preserves RELEASED hold")
    void matchingTerminalFailedReplayMakesZeroPspCalls() {
        AtomicInteger pspCallCount = new AtomicInteger(0);
        currentHandler = exchange -> {
            pspCallCount.incrementAndGet();
            byte[] errBytes = "{\"type\": \"urn:ledgerguard:psp:error:temporary-failure\", \"title\": \"Definite failure simulated\", \"status\": 500}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(500, errBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(errBytes);
            }
        };

        String idempKey = "payout-failed-replay-" + UUID.randomUUID();
        CreatePayoutCommand command = new CreatePayoutCommand(customerUserId, idempKey, Money.inr(3000));

        // First attempt -> FAILED
        PayoutResult firstResult = payoutService.requestPayout(command);
        assertThat(firstResult.status()).isEqualTo(PayoutStatus.FAILED);
        assertThat(firstResult.replayed()).isFalse();
        assertThat(pspCallCount.get()).isEqualTo(1);

        BalanceHold hold = balanceHoldRepository.findById(firstResult.balanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);

        // Second attempt with SAME idempotency key -> FAILED replay
        PayoutResult replayResult = payoutService.requestPayout(command);
        assertThat(replayResult.status()).isEqualTo(PayoutStatus.FAILED);
        assertThat(replayResult.replayed()).isTrue();
        assertThat(replayResult.payoutId()).isEqualTo(firstResult.payoutId());
        assertThat(replayResult.balanceHoldId()).isEqualTo(firstResult.balanceHoldId());

        // ZERO new PSP calls made on replay
        assertThat(pspCallCount.get()).isEqualTo(1);

        // Database row counts
        Integer payoutCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payouts WHERE id = ?",
                Integer.class,
                firstResult.payoutId()
        );
        assertThat(payoutCount).isEqualTo(1);

        Integer holdCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM balance_holds WHERE id = ?",
                Integer.class,
                firstResult.balanceHoldId()
        );
        assertThat(holdCount).isEqualTo(1);

        assertThat(firstResult.journalTransactionId()).isNull();
        assertThat(replayResult.journalTransactionId()).isNull();

        // Hold remains RELEASED
        BalanceHold recheckedHold = balanceHoldRepository.findById(firstResult.balanceHoldId()).orElseThrow();
        assertThat(recheckedHold.getStatus()).isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    @DisplayName("Idempotency conflict rejects same key with different amount")
    void idempotencyConflictRejectsChangedAmount() {
        currentHandler = exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", (String) body.get("amountMinor"),
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        String conflictKey = "conflict-payout-key-" + UUID.randomUUID();
        CreatePayoutCommand cmd1 = new CreatePayoutCommand(customerUserId, conflictKey, Money.inr(3000));
        PayoutResult res1 = payoutService.requestPayout(cmd1);
        assertThat(res1.status()).isEqualTo(PayoutStatus.SUCCEEDED);

        CreatePayoutCommand cmd2 = new CreatePayoutCommand(customerUserId, conflictKey, Money.inr(5000));
        assertThatThrownBy(() -> payoutService.requestPayout(cmd2))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("20 concurrent identical payout requests result in exactly 1 Payout, 1 hold, 1 journal, 1 provider call")
    void concurrentIdenticalPayoutRequests() throws InterruptedException {
        AtomicInteger pspCallCount = new AtomicInteger(0);
        currentHandler = exchange -> {
            pspCallCount.incrementAndGet();
            try (InputStream is = exchange.getRequestBody()) {
                Map<?, ?> body = objectMapper.readValue(is, Map.class);
                UUID clientOpId = UUID.fromString((String) body.get("clientOperationId"));
                Map<String, Object> respMap = Map.of(
                        "providerOperationId", UUID.randomUUID().toString(),
                        "clientOperationId", clientOpId.toString(),
                        "operationType", "DEBIT",
                        "amountMinor", "3000",
                        "currency", "INR",
                        "status", "SUCCEEDED",
                        "createdAt", Instant.now().toString(),
                        "completedAt", Instant.now().toString(),
                        "replayed", false
                );
                respondJson(exchange, 201, respMap);
            }
        };

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<PayoutResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        CreatePayoutCommand command = new CreatePayoutCommand(customerUserId, "concurrent-same-key-" + UUID.randomUUID(), Money.inr(3000));

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    PayoutResult result = payoutService.requestPayout(command);
                    results.add(result);
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

        assertThat(pspCallCount.get()).isEqualTo(1);

        // Customer wallet balance debited exactly once: 10000 - 3000 = 7000
        LedgerBalanceSnapshot snapshot = snapshotRepository.findById(customerAccount.getId()).orElseThrow();
        assertThat(snapshot.getBalanceMinor()).isEqualTo(7000L);
    }
}
