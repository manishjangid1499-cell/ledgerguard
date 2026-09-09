package com.ledgerguard.lab.perf;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import com.ledgerguard.LedgerGuardApplication;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.invariants.FinancialInvariantOracle;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.transfer.application.CreateTransferCommand;
import com.ledgerguard.transfer.application.TransferService;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = LedgerGuardApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("Phase 39 Concurrency & Contention Performance Benchmark")
public class ConcurrencyBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyBenchmark.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String RUNTIME_DB_PASSWORD = generateSecret();
    private static final String RUNTIME_JWT_SECRET = generateSecret();
    private static final String RUNTIME_WEBHOOK_SECRET = generateSecret();

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine")
                    .withDatabaseName("ledgerguard_perf")
                    .withUsername("perf_user")
                    .withPassword(RUNTIME_DB_PASSWORD)
                    .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> Integer.getInteger("benchmark.pool.size", 10));
        registry.add("ledgerguard.outbox.publisher.enabled", () -> "false");
        registry.add("ledgerguard.psp.polling.enabled", () -> "false");
        registry.add("ledgerguard.metrics.integrity.scheduler-enabled", () -> "false");
        registry.add("ledgerguard.security.jwt.secret", () -> RUNTIME_JWT_SECRET);
        registry.add("ledgerguard.psp.webhook.secret", () -> RUNTIME_WEBHOOK_SECRET);
        // Provide harmless Kafka bootstrap config; outbox publisher is disabled
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:29092");
    }

    @Autowired
    private TransferService transferService;

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private final FinancialInvariantOracle oracle = new FinancialInvariantOracle();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runBenchmark() throws Exception {
        String rawMode = System.getProperty("benchmark.mode", "baseline");
        String mode = rawMode == null ? "baseline" : rawMode.trim().toLowerCase();
        if (!"smoke".equals(mode) && !"baseline".equals(mode) && !"pool-comparison".equals(mode)) {
            throw new IllegalArgumentException(
                    "Unsupported benchmark.mode '" + rawMode + "'. Allowed values: smoke, baseline, pool-comparison");
        }

        HikariDataSource hikariDs = dataSource.unwrap(HikariDataSource.class);
        int poolSize = hikariDs.getMaximumPoolSize();

        log.info("================================================================================");
        log.info("STARTING PHASE 39 CONCURRENCY BENCHMARK [mode={}, poolSize={}]", mode, poolSize);
        log.info("Effective Hikari config: max={}, minIdle={}, connTimeout={}ms, idleTimeout={}ms, maxLifetime={}ms, keepalive={}ms",
                hikariDs.getMaximumPoolSize(), hikariDs.getMinimumIdle(), hikariDs.getConnectionTimeout(),
                hikariDs.getIdleTimeout(), hikariDs.getMaxLifetime(), hikariDs.getKeepaliveTime());
        log.info("================================================================================");

        // Ensure system clearing account exists
        getOrCreatePspClearing();

        // 1. Warmup
        log.info("Performing 20 unmeasured warmup operations...");
        performWarmup();
        log.info("Warmup complete.");

        com.zaxxer.hikari.HikariPoolMXBean poolBean = hikariDs.getHikariPoolMXBean();
        int postWarmupTotal = poolBean != null ? poolBean.getTotalConnections() : -1;
        int postWarmupActive = poolBean != null ? poolBean.getActiveConnections() : -1;
        int postWarmupIdle = poolBean != null ? poolBean.getIdleConnections() : -1;
        log.info("Post-warmup Hikari pool readiness: total={}, active={}, idle={}, maxPoolSize={}, minimumIdle={}",
                postWarmupTotal, postWarmupActive, postWarmupIdle, hikariDs.getMaximumPoolSize(), hikariDs.getMinimumIdle());

        List<BenchmarkRunResult> results = new ArrayList<>();
        long benchmarkStartNs = System.nanoTime();

        switch (mode) {
            case "smoke" -> runSmokeMode(hikariDs, poolSize, results);
            case "baseline" -> runBaselineMode(hikariDs, poolSize, results);
            case "pool-comparison" -> runPoolComparisonMode(hikariDs, poolSize, results);
            default -> throw new IllegalArgumentException(
                    "Unsupported benchmark.mode '" + rawMode + "'. Allowed values: smoke, baseline, pool-comparison");
        }

        long totalElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - benchmarkStartNs);
        log.info("All benchmark scenarios completed in {} ms (total runs: {})", totalElapsedMs, results.size());

        // Write outputs first so diagnostic JSON/CSV remain available even if an assertion fails
        writeOutputs(hikariDs, poolSize, mode, totalElapsedMs, postWarmupTotal, postWarmupActive, postWarmupIdle, results);

        // Fail benchmark if any operation failed or invariants were violated
        for (BenchmarkRunResult r : results) {
            assertEquals(0, r.failureCount(),
                    () -> String.format("Benchmark scenario %s @ c%d r%d had %d operation failures",
                            r.workload(), r.concurrency(), r.repetition(), r.failureCount()));
            assertEquals(r.operationsRequested(), r.successCount(),
                    () -> String.format("Benchmark scenario %s @ c%d r%d expected %d successes but got %d",
                            r.workload(), r.concurrency(), r.repetition(), r.operationsRequested(), r.successCount()));
            assertTrue(r.financialInvariantsPassed(),
                    () -> String.format("Benchmark scenario %s @ c%d r%d failed financial invariant assertions",
                            r.workload(), r.concurrency(), r.repetition()));
        }
    }

    private void runSmokeMode(HikariDataSource hikariDs, int poolSize, List<BenchmarkRunResult> results) throws Exception {
        // Smoke: 2 workers, 10 ops, 1 repetition
        results.add(executeWorkloadA(hikariDs, poolSize, 2, 10, 1));
    }

    private void runBaselineMode(HikariDataSource hikariDs, int poolSize, List<BenchmarkRunResult> results) throws Exception {
        // Workload A (LOW_CONTENTION): concurrency = 1, 5, 10, 20, 50 (100 ops, 3 reps)
        int[] concA = {1, 5, 10, 20, 50};
        for (int c : concA) {
            for (int r = 1; r <= 3; r++) {
                results.add(executeWorkloadA(hikariDs, poolSize, c, 100, r));
            }
        }

        // Workload B (HOT_ACCOUNT): concurrency = 10, 20, 50 (100 ops, 3 reps)
        int[] concB = {10, 20, 50};
        for (int c : concB) {
            for (int r = 1; r <= 3; r++) {
                results.add(executeWorkloadB(hikariDs, poolSize, c, 100, r));
            }
        }

        // Workload C (OPPOSING_TRANSFERS): concurrency = 10, 20, 50 (100 ops, 3 reps)
        int[] concC = {10, 20, 50};
        for (int c : concC) {
            for (int r = 1; r <= 3; r++) {
                results.add(executeWorkloadC(hikariDs, poolSize, c, 100, r));
            }
        }
    }

    private void runPoolComparisonMode(HikariDataSource hikariDs, int poolSize, List<BenchmarkRunResult> results) throws Exception {
        // Workload A (LOW_CONTENTION): concurrency = 20, 50 (100 ops, 3 reps)
        int[] concA = {20, 50};
        for (int c : concA) {
            for (int r = 1; r <= 3; r++) {
                results.add(executeWorkloadA(hikariDs, poolSize, c, 100, r));
            }
        }

        // Workload B (HOT_ACCOUNT): concurrency = 20, 50 (100 ops, 3 reps)
        int[] concB = {20, 50};
        for (int c : concB) {
            for (int r = 1; r <= 3; r++) {
                results.add(executeWorkloadB(hikariDs, poolSize, c, 100, r));
            }
        }
    }

    // =========================================================================
    // WORKLOAD A: LOW CONTENTION
    // =========================================================================
    private BenchmarkRunResult executeWorkloadA(
            HikariDataSource hikariDs, int poolSize, int concurrency, int totalOps, int rep
    ) throws Exception {
        log.info("[Workload A: LOW_CONTENTION] pool={}, concurrency={}, ops={}, rep={}", poolSize, concurrency, totalOps, rep);

        // Create C independent account pairs (A_i -> B_i)
        List<UUID> senders = new ArrayList<>();
        List<UUID> receivers = new ArrayList<>();
        List<UUID> participantAccountIds = new ArrayList<>();
        long fundingPerSender = 10_000_000L; // 100,000 INR, plenty for 100 ops of 100 paise each

        for (int i = 0; i < concurrency; i++) {
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();
            userRepository.save(new User(userA, "senderA_" + userA + "@bench.local", "hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
            userRepository.save(new User(userB, "receiverB_" + userB + "@bench.local", "hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

            LedgerAccount accA = accountRepository.save(LedgerAccount.createCustomerAccount(userA));
            LedgerAccount accB = accountRepository.save(LedgerAccount.createCustomerAccount(userB));

            senders.add(accA.getId());
            receivers.add(accB.getId());
            participantAccountIds.add(accA.getId());
            participantAccountIds.add(accB.getId());

            fundAccount(accA.getId(), fundingPerSender);
        }

        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector(
                hikariDs, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), meterRegistry
        );

        ConcurrentLinkedQueue<Double> latenciesMs = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        Map<String, Integer> errorMap = new ConcurrentHashMap<>();

        // Distribute totalOps across workers
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            final int workerIdx = i;
            final UUID senderAcc = senders.get(workerIdx);
            final UUID receiverAcc = receivers.get(workerIdx);
            final UUID actorUserId = accountRepository.findById(senderAcc).orElseThrow().getOwnerUserId();

            int opsForWorker = totalOps / concurrency + (workerIdx < (totalOps % concurrency) ? 1 : 0);

            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                for (int j = 0; j < opsForWorker; j++) {
                    CreateTransferCommand cmd = new CreateTransferCommand(
                            actorUserId, receiverAcc, Money.inr(100), "idem-a-" + UUID.randomUUID()
                    );
                    long startNs = System.nanoTime();
                    try {
                        transferService.createTransfer(cmd);
                        long durNs = System.nanoTime() - startNs;
                        latenciesMs.add(durNs / 1_000_000.0);
                        successCount.incrementAndGet();
                    } catch (Exception ex) {
                        long durNs = System.nanoTime() - startNs;
                        latenciesMs.add(durNs / 1_000_000.0);
                        failureCount.incrementAndGet();
                        classifyError(ex, errorMap);
                    }
                }
            }));
        }

        collector.start();
        long wallStartNs = System.nanoTime();
        startGate.countDown();

        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }

        long wallElapsedNs = System.nanoTime() - wallStartNs;
        collector.stop();
        executor.shutdown();

        double elapsedMs = wallElapsedNs / 1_000_000.0;
        double tps = elapsedMs > 0 ? (successCount.get() / (elapsedMs / 1000.0)) : 0.0;

        // Verify financial invariants
        boolean invariantsPassed = verifyFinancialInvariants(participantAccountIds, null, null, null);
        assertThat(invariantsPassed).as("Financial invariants must pass for Workload A").isTrue();

        return buildResult(poolSize, "LOW_CONTENTION", concurrency, rep, totalOps, successCount.get(),
                failureCount.get(), elapsedMs, tps, latenciesMs, collector, invariantsPassed, errorMap);
    }

    // =========================================================================
    // WORKLOAD B: HOT ACCOUNT
    // =========================================================================
    private BenchmarkRunResult executeWorkloadB(
            HikariDataSource hikariDs, int poolSize, int concurrency, int totalOps, int rep
    ) throws Exception {
        log.info("[Workload B: HOT_ACCOUNT] pool={}, concurrency={}, ops={}, rep={}", poolSize, concurrency, totalOps, rep);

        // Create 1 hot central receiver account
        UUID hotUser = UUID.randomUUID();
        userRepository.save(new User(hotUser, "hotReceiver_" + hotUser + "@bench.local", "hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        LedgerAccount hotAccount = accountRepository.save(LedgerAccount.createCustomerAccount(hotUser));

        // Create totalOps distinct sender accounts, each with 10,000 minor units
        List<UUID> senderAccounts = new ArrayList<>();
        List<UUID> senderUsers = new ArrayList<>();
        List<UUID> allParticipants = new ArrayList<>();
        allParticipants.add(hotAccount.getId());

        long initialPerSender = 10_000L;
        for (int i = 0; i < totalOps; i++) {
            UUID senderUser = UUID.randomUUID();
            userRepository.save(new User(senderUser, "hotSender_" + i + "_" + senderUser + "@bench.local", "hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
            LedgerAccount senderAcc = accountRepository.save(LedgerAccount.createCustomerAccount(senderUser));
            senderAccounts.add(senderAcc.getId());
            senderUsers.add(senderUser);
            allParticipants.add(senderAcc.getId());
            fundAccount(senderAcc.getId(), initialPerSender);
        }

        // Record total balance of participants before run
        long preTotalBalance = queryTotalBalances(allParticipants);

        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector(
                hikariDs, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), meterRegistry
        );

        ConcurrentLinkedQueue<Double> latenciesMs = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        Map<String, Integer> errorMap = new ConcurrentHashMap<>();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger nextOpIndex = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                while (true) {
                    int idx = nextOpIndex.getAndIncrement();
                    if (idx >= totalOps) {
                        break;
                    }

                    UUID sAcc = senderAccounts.get(idx);
                    UUID sUser = senderUsers.get(idx);
                    CreateTransferCommand cmd = new CreateTransferCommand(
                            sUser, hotAccount.getId(), Money.inr(500), "idem-hot-" + UUID.randomUUID()
                    );

                    long startNs = System.nanoTime();
                    try {
                        transferService.createTransfer(cmd);
                        long durNs = System.nanoTime() - startNs;
                        latenciesMs.add(durNs / 1_000_000.0);
                        successCount.incrementAndGet();
                    } catch (Exception ex) {
                        long durNs = System.nanoTime() - startNs;
                        latenciesMs.add(durNs / 1_000_000.0);
                        failureCount.incrementAndGet();
                        classifyError(ex, errorMap);
                    }
                }
            }));
        }

        collector.start();
        long wallStartNs = System.nanoTime();
        startGate.countDown();

        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }

        long wallElapsedNs = System.nanoTime() - wallStartNs;
        collector.stop();
        executor.shutdown();

        double elapsedMs = wallElapsedNs / 1_000_000.0;
        double tps = elapsedMs > 0 ? (successCount.get() / (elapsedMs / 1000.0)) : 0.0;

        // Verify financial invariants + conservation of participant set
        boolean invariantsPassed = verifyFinancialInvariants(allParticipants, null, null, preTotalBalance);
        assertThat(invariantsPassed).as("Financial invariants must pass for Workload B").isTrue();

        return buildResult(poolSize, "HOT_ACCOUNT", concurrency, rep, totalOps, successCount.get(),
                failureCount.get(), elapsedMs, tps, latenciesMs, collector, invariantsPassed, errorMap);
    }

    // =========================================================================
    // WORKLOAD C: OPPOSING TRANSFERS
    // =========================================================================
    private BenchmarkRunResult executeWorkloadC(
            HikariDataSource hikariDs, int poolSize, int concurrency, int totalOps, int rep
    ) throws Exception {
        log.info("[Workload C: OPPOSING_TRANSFERS] pool={}, concurrency={}, ops={}, rep={}", poolSize, concurrency, totalOps, rep);

        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        userRepository.save(new User(userA, "oppUserA_" + userA + "@bench.local", "hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        userRepository.save(new User(userB, "oppUserB_" + userB + "@bench.local", "hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        LedgerAccount accA = accountRepository.save(LedgerAccount.createCustomerAccount(userA));
        LedgerAccount accB = accountRepository.save(LedgerAccount.createCustomerAccount(userB));

        long initialEach = 10_000_000L;
        fundAccount(accA.getId(), initialEach);
        fundAccount(accB.getId(), initialEach);
        long initialSum = initialEach * 2;

        List<UUID> participants = List.of(accA.getId(), accB.getId());

        BenchmarkMetricsCollector collector = new BenchmarkMetricsCollector(
                hikariDs, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), meterRegistry
        );

        ConcurrentLinkedQueue<Double> latenciesMs = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        Map<String, Integer> errorMap = new ConcurrentHashMap<>();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger opCounter = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                while (true) {
                    int opIdx = opCounter.getAndIncrement();
                    if (opIdx >= totalOps) {
                        break;
                    }

                    // 50% A->B, 50% B->A
                    CreateTransferCommand cmd;
                    if (opIdx % 2 == 0) {
                        cmd = new CreateTransferCommand(userA, accB.getId(), Money.inr(500), "idem-opp-ab-" + UUID.randomUUID());
                    } else {
                        cmd = new CreateTransferCommand(userB, accA.getId(), Money.inr(500), "idem-opp-ba-" + UUID.randomUUID());
                    }

                    long startNs = System.nanoTime();
                    try {
                        transferService.createTransfer(cmd);
                        long durNs = System.nanoTime() - startNs;
                        latenciesMs.add(durNs / 1_000_000.0);
                        successCount.incrementAndGet();
                    } catch (Exception ex) {
                        long durNs = System.nanoTime() - startNs;
                        latenciesMs.add(durNs / 1_000_000.0);
                        failureCount.incrementAndGet();
                        classifyError(ex, errorMap);
                    }
                }
            }));
        }

        collector.start();
        long wallStartNs = System.nanoTime();
        startGate.countDown();

        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }

        long wallElapsedNs = System.nanoTime() - wallStartNs;
        collector.stop();
        executor.shutdown();

        double elapsedMs = wallElapsedNs / 1_000_000.0;
        double tps = elapsedMs > 0 ? (successCount.get() / (elapsedMs / 1000.0)) : 0.0;

        // Verify financial invariants
        boolean invariantsPassed = verifyFinancialInvariants(participants, accA.getId(), accB.getId(), initialSum);
        assertThat(invariantsPassed).as("Financial invariants must pass for Workload C").isTrue();

        return buildResult(poolSize, "OPPOSING_TRANSFERS", concurrency, rep, totalOps, successCount.get(),
                failureCount.get(), elapsedMs, tps, latenciesMs, collector, invariantsPassed, errorMap);
    }

    // =========================================================================
    // INVARIANT VERIFICATION
    // =========================================================================
    private boolean verifyFinancialInvariants(
            List<UUID> participantIds, UUID accA, UUID accB, Long expectedExactSum
    ) {
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            List<InvariantCheckResult> r1 = oracle.checkJournalStructure(conn);
            for (InvariantCheckResult res : r1) {
                if (!res.passed()) {
                    log.error("FAILED JOURNAL STRUCTURE INVARIANT: {}", res.message());
                    return false;
                }
            }

            List<InvariantCheckResult> r2 = oracle.checkDebitCreditEquality(conn);
            for (InvariantCheckResult res : r2) {
                if (!res.passed()) {
                    log.error("FAILED DEBIT CREDIT INVARIANT: {}", res.message());
                    return false;
                }
            }

            UUID[] accArr = participantIds.toArray(new UUID[0]);
            List<InvariantCheckResult> r3 = oracle.checkSnapshotIntegrity(conn, accArr);
            for (InvariantCheckResult res : r3) {
                if (!res.passed()) {
                    log.error("FAILED SNAPSHOT INTEGRITY INVARIANT: {}", res.message());
                    return false;
                }
            }

            if (accA != null && accB != null && expectedExactSum != null) {
                InvariantCheckResult r4 = oracle.checkInternalTransferConservation(conn, accA, accB, expectedExactSum);
                if (!r4.passed()) {
                    log.error("FAILED TRANSFER CONSERVATION INVARIANT: {}", r4.message());
                    return false;
                }
            }

            if (expectedExactSum != null && (accA == null || accB == null)) {
                // Hot account total participant sum check
                long actualTotal = queryTotalBalances(participantIds);
                if (actualTotal != expectedExactSum) {
                    log.error("FAILED PARTICIPANT SET CONSERVATION: expected={}, actual={}", expectedExactSum, actualTotal);
                    return false;
                }
            }

            return true;
        } catch (SQLException e) {
            log.error("Exception during invariant check: {}", e.getMessage(), e);
            return false;
        }
    }

    private long queryTotalBalances(List<UUID> accountIds) {
        if (accountIds.isEmpty()) return 0;
        String inSql = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        Long total = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(balance_minor), 0) FROM ledger_balance_snapshots WHERE ledger_account_id IN (" + inSql + ")",
                Long.class,
                accountIds.toArray()
        );
        return total != null ? total : 0L;
    }

    private void performWarmup() {
        UUID userW1 = UUID.randomUUID();
        UUID userW2 = UUID.randomUUID();
        userRepository.save(new User(userW1, "warmup1_" + userW1 + "@bench.local", "hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        userRepository.save(new User(userW2, "warmup2_" + userW2 + "@bench.local", "hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        LedgerAccount acc1 = accountRepository.save(LedgerAccount.createCustomerAccount(userW1));
        LedgerAccount acc2 = accountRepository.save(LedgerAccount.createCustomerAccount(userW2));
        fundAccount(acc1.getId(), 100_000L);

        for (int i = 0; i < 20; i++) {
            transferService.createTransfer(new CreateTransferCommand(
                    userW1, acc2.getId(), Money.inr(100), "idem-warmup-" + UUID.randomUUID()
            ));
        }
    }

    private void fundAccount(UUID accountId, long amountMinor) {
        LedgerAccount clearing = getOrCreatePspClearing();
        postingService.post(new PostJournalCommand(List.of(
                PostingLine.debit(clearing.getId(), amountMinor),
                PostingLine.credit(accountId, amountMinor)
        )));
    }

    private LedgerAccount getOrCreatePspClearing() {
        return accountRepository.findAllByAccountType(AccountType.PSP_CLEARING).stream()
                .filter(a -> a.getStatus() == com.ledgerguard.ledger.domain.AccountStatus.ACTIVE && "INR".equals(a.getCurrency()) && a.getOwnerUserId() == null)
                .findFirst()
                .orElseGet(() -> accountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));
    }

    private void classifyError(Exception ex, Map<String, Integer> errorMap) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        String cls = ex.getClass().getSimpleName();
        if (msg.contains("40P01") || msg.toLowerCase().contains("deadlock")) {
            errorMap.merge("PostgreSQL Deadlock (40P01)", 1, Integer::sum);
        } else if (msg.toLowerCase().contains("timeout") && msg.toLowerCase().contains("connection")) {
            errorMap.merge("Hikari Connection Timeout", 1, Integer::sum);
        } else if (msg.contains("Insufficient funds") || msg.contains("Insufficient")) {
            errorMap.merge("Insufficient Funds", 1, Integer::sum);
        } else {
            errorMap.merge(cls + ": " + (msg.length() > 50 ? msg.substring(0, 50) : msg), 1, Integer::sum);
        }
    }

    private BenchmarkRunResult buildResult(
            int poolSize, String workload, int concurrency, int rep, int totalOps,
            int successCount, int failureCount, double elapsedMs, double tps,
            ConcurrentLinkedQueue<Double> latenciesMs, BenchmarkMetricsCollector collector,
            boolean invariantsPassed, Map<String, Integer> errorMap
    ) {
        List<Double> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);

        double min = sorted.isEmpty() ? 0.0 : sorted.get(0);
        double max = sorted.isEmpty() ? 0.0 : sorted.get(sorted.size() - 1);
        double sum = 0.0;
        for (double d : sorted) sum += d;
        double avg = sorted.isEmpty() ? 0.0 : sum / sorted.size();

        double p50 = computePercentile(sorted, 50.0);
        double p95 = computePercentile(sorted, 95.0);
        double p99 = computePercentile(sorted, 99.0);

        return new BenchmarkRunResult(
                poolSize, workload, concurrency, rep, totalOps, successCount, failureCount,
                elapsedMs, tps, min, avg, p50, p95, p99, max,
                collector.getMaxActiveConnections(), collector.getMinIdleConnections(),
                collector.getMaxPendingThreads(), collector.getSamplesWithPendingThreads(),
                collector.isAcquisitionMetricAvailable(), collector.getAcquisitionCountDelta(),
                collector.getAcquisitionTotalMsDelta(), collector.getMaxDatabaseLockWaiters(),
                collector.getSamplesWithDatabaseLockWaiters(), collector.getDeadlocksBefore(),
                collector.getDeadlocksAfter(), collector.getDeadlocksDelta(), invariantsPassed, errorMap
        );
    }

    private double computePercentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0.0;
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private void writeOutputs(
            HikariDataSource hikariDs, int poolSize, String mode, long totalElapsedMs,
            int postWarmupTotal, int postWarmupActive, int postWarmupIdle,
            List<BenchmarkRunResult> results
    ) throws Exception {
        File dir = new File("target/phase39-benchmarks");
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String baseName = "benchmark-pool-" + poolSize + "-" + mode;
        File jsonFile = new File(dir, baseName + ".json");
        File csvFile = new File(dir, baseName + ".csv");

        // Environment metadata
        String pgVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
        long maxMemoryBytes = Runtime.getRuntime().maxMemory();
        String hikariVersion = HikariDataSource.class.getPackage().getImplementationVersion();
        if (hikariVersion == null || hikariVersion.isBlank()) {
            hikariVersion = "UNAVAILABLE (Spring Boot managed)";
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("generatedAt", Instant.now().toString());
        root.put("benchmarkMode", mode);
        root.put("poolSize", poolSize);
        root.put("totalElapsedMs", totalElapsedMs);

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("osName", System.getProperty("os.name"));
        env.put("osVersion", System.getProperty("os.version"));
        env.put("osArch", System.getProperty("os.arch"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("javaVersion", System.getProperty("java.version"));
        env.put("javaVendor", System.getProperty("java.vendor"));
        env.put("jvmName", ManagementFactory.getRuntimeMXBean().getVmName());
        env.put("jvmMaxMemoryBytes", maxMemoryBytes);
        env.put("jvmMaxMemoryMb", maxMemoryBytes / (1024 * 1024));
        env.put("postgreSqlVersion", pgVersion);
        root.put("environment", env);

        Map<String, Object> hikariMeta = new LinkedHashMap<>();
        hikariMeta.put("implementationVersion", hikariVersion);
        hikariMeta.put("poolName", hikariDs.getPoolName());
        hikariMeta.put("maximumPoolSize", hikariDs.getMaximumPoolSize());
        hikariMeta.put("minimumIdle", hikariDs.getMinimumIdle());
        hikariMeta.put("connectionTimeoutMs", hikariDs.getConnectionTimeout());
        hikariMeta.put("idleTimeoutMs", hikariDs.getIdleTimeout());
        hikariMeta.put("maxLifetimeMs", hikariDs.getMaxLifetime());
        hikariMeta.put("keepaliveTimeMs", hikariDs.getKeepaliveTime());
        hikariMeta.put("postWarmupTotalConnections", postWarmupTotal);
        hikariMeta.put("postWarmupActiveConnections", postWarmupActive);
        hikariMeta.put("postWarmupIdleConnections", postWarmupIdle);
        root.put("hikariSettings", hikariMeta);

        root.put("results", results);

        String jsonStr = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(jsonFile.toPath(), jsonStr);
        log.info("Wrote JSON benchmark output to: {}", jsonFile.getAbsolutePath());

        // CSV output
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvFile))) {
            pw.println("poolSize,workload,concurrency,repetition,operationsRequested,successCount,failureCount," +
                    "elapsedMs,throughputOpsPerSecond,latencyMinMs,latencyAverageMs,latencyP50Ms,latencyP95Ms,latencyP99Ms,latencyMaxMs," +
                    "maxActiveConnections,minIdleConnections,maxPendingThreads,samplesWithPendingThreads," +
                    "maxDatabaseLockWaiters,samplesWithDatabaseLockWaiters,deadlocksDelta,financialInvariantsPassed");
            for (BenchmarkRunResult r : results) {
                pw.printf("%d,%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%d,%d,%b%n",
                        r.poolSize(), r.workload(), r.concurrency(), r.repetition(), r.operationsRequested(),
                        r.successCount(), r.failureCount(), r.elapsedMs(), r.throughputOpsPerSecond(),
                        r.latencyMinMs(), r.latencyAverageMs(), r.latencyP50Ms(), r.latencyP95Ms(), r.latencyP99Ms(), r.latencyMaxMs(),
                        r.maxActiveConnections(), r.minIdleConnections(), r.maxPendingThreads(), r.samplesWithPendingThreads(),
                        r.maxDatabaseLockWaiters(), r.samplesWithDatabaseLockWaiters(), r.deadlocksDelta(), r.financialInvariantsPassed());
            }
        }
        log.info("Wrote CSV benchmark output to: {}", csvFile.getAbsolutePath());
    }
}
