package com.ledgerguard.idempotency.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.idempotency.domain.IdempotencyConflictException;
import com.ledgerguard.idempotency.domain.IdempotencyRecord;
import com.ledgerguard.idempotency.domain.IdempotencyStatus;
import com.ledgerguard.idempotency.domain.RequestFingerprint;
import com.ledgerguard.idempotency.infrastructure.IdempotencyRecordRepository;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.application.PostingResult;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Basic success execution creates completed idempotency record and returns replayed=false")
    void basicSuccessExecution() {
        UUID actorId = createTestUser();
        LedgerAccount reserve = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount clearing = createSystemAccount(AccountType.PSP_CLEARING);

        String key = "key-basic-" + UUID.randomUUID();
        String operation = "TEST_POST";
        RequestFingerprint fingerprint = RequestFingerprint.of("test-payload-1");

        IdempotencyCommand command = IdempotencyCommand.of(actorId, operation, key, fingerprint);

        AtomicInteger invocations = new AtomicInteger(0);
        IdempotencyExecutionResult result = idempotencyService.execute(command, () -> {
            invocations.incrementAndGet();
            PostingResult postingResult = ledgerPostingService.post(PostJournalCommand.of(
                    PostingLine.debit(reserve.getId(), 10000L),
                    PostingLine.credit(clearing.getId(), 10000L)
            ));
            return postingResult.journalTransactionId();
        });

        assertThat(invocations.get()).isEqualTo(1);
        assertThat(result.replayed()).isFalse();
        assertThat(result.resultId()).isNotNull();

        // Verify idempotency record in DB
        IdempotencyRecord record = idempotencyRecordRepository
                .findByActorUserIdAndOperationAndIdempotencyKey(actorId, operation, key)
                .orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getResultId()).isEqualTo(result.resultId());
        assertThat(record.getRequestFingerprint()).isEqualTo(fingerprint.getValue());
        assertThat(record.getCompletedAt()).isNotNull();

        // Verify financial effect
        assertThat(journalTransactionRepository.findById(result.resultId())).isPresent();
        assertThat(getSnapshotBalance(reserve.getId())).isEqualTo(10000L);
        assertThat(getSnapshotBalance(clearing.getId())).isEqualTo(-10000L);
    }

    @Test
    @DisplayName("Sequential replay returns stored result without re-invoking callback or modifying balances")
    void sequentialReplay() {
        UUID actorId = createTestUser();
        LedgerAccount reserve = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount clearing = createSystemAccount(AccountType.PSP_CLEARING);

        String key = "key-replay-" + UUID.randomUUID();
        String operation = "TEST_POST";
        RequestFingerprint fingerprint = RequestFingerprint.of("test-payload-replay");
        IdempotencyCommand command = IdempotencyCommand.of(actorId, operation, key, fingerprint);

        AtomicInteger invocations = new AtomicInteger(0);

        // First call
        IdempotencyExecutionResult firstResult = idempotencyService.execute(command, () -> {
            invocations.incrementAndGet();
            PostingResult postingResult = ledgerPostingService.post(PostJournalCommand.of(
                    PostingLine.debit(reserve.getId(), 5000L),
                    PostingLine.credit(clearing.getId(), 5000L)
            ));
            return postingResult.journalTransactionId();
        });

        assertThat(firstResult.replayed()).isFalse();
        assertThat(invocations.get()).isEqualTo(1);
        long initialJournalCount = journalTransactionRepository.count();

        // Second call (replay)
        IdempotencyExecutionResult replayResult = idempotencyService.execute(command, () -> {
            invocations.incrementAndGet();
            PostingResult postingResult = ledgerPostingService.post(PostJournalCommand.of(
                    PostingLine.debit(reserve.getId(), 5000L),
                    PostingLine.credit(clearing.getId(), 5000L)
            ));
            return postingResult.journalTransactionId();
        });

        assertThat(replayResult.replayed()).isTrue();
        assertThat(replayResult.resultId()).isEqualTo(firstResult.resultId());
        assertThat(invocations.get()).isEqualTo(1); // Callback was NOT invoked second time

        // Financial state unchanged by replay
        assertThat(journalTransactionRepository.count()).isEqualTo(initialJournalCount);
        assertThat(getSnapshotBalance(reserve.getId())).isEqualTo(5000L);
        assertThat(getSnapshotBalance(clearing.getId())).isEqualTo(-5000L);
    }

    @Test
    @DisplayName("Same key with different request fingerprint throws IdempotencyConflictException without modifying data")
    void sameKeyDifferentFingerprintConflict() {
        UUID actorId = createTestUser();
        String key = "key-conflict-" + UUID.randomUUID();
        String operation = "TEST_POST";
        RequestFingerprint fp1 = RequestFingerprint.of("request-1");
        RequestFingerprint fp2 = RequestFingerprint.of("request-2");

        UUID firstResultId = UUID.randomUUID();
        idempotencyService.execute(IdempotencyCommand.of(actorId, operation, key, fp1), () -> firstResultId);

        // Attempt same key with fp2
        AtomicInteger invocations = new AtomicInteger(0);
        assertThatThrownBy(() -> idempotencyService.execute(
                IdempotencyCommand.of(actorId, operation, key, fp2),
                () -> {
                    invocations.incrementAndGet();
                    return UUID.randomUUID();
                }
        )).isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request fingerprint");

        assertThat(invocations.get()).isEqualTo(0);

        // Original record is unchanged
        IdempotencyRecord record = idempotencyRecordRepository
                .findByActorUserIdAndOperationAndIdempotencyKey(actorId, operation, key)
                .orElseThrow();
        assertThat(record.getResultId()).isEqualTo(firstResultId);
        assertThat(record.getRequestFingerprint()).isEqualTo(fp1.getValue());
    }

    @Test
    @DisplayName("Same actor and key with different operations execute independently")
    void sameKeyDifferentOperations() {
        UUID actorId = createTestUser();
        String key = "key-shared-" + UUID.randomUUID();
        RequestFingerprint fp = RequestFingerprint.of("payload");

        UUID result1 = UUID.randomUUID();
        UUID result2 = UUID.randomUUID();

        IdempotencyExecutionResult res1 = idempotencyService.execute(
                IdempotencyCommand.of(actorId, "OP_A", key, fp), () -> result1
        );
        IdempotencyExecutionResult res2 = idempotencyService.execute(
                IdempotencyCommand.of(actorId, "OP_B", key, fp), () -> result2
        );

        assertThat(res1.replayed()).isFalse();
        assertThat(res2.replayed()).isFalse();
        assertThat(res1.resultId()).isEqualTo(result1);
        assertThat(res2.resultId()).isEqualTo(result2);

        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(actorId, "OP_A", key)).isPresent();
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(actorId, "OP_B", key)).isPresent();
    }

    @Test
    @DisplayName("Different actors using the same key and operation execute independently")
    void differentActorsSameKey() {
        UUID actor1 = createTestUser();
        UUID actor2 = createTestUser();
        String key = "key-multi-actor-" + UUID.randomUUID();
        String operation = "TRANSFER";
        RequestFingerprint fp = RequestFingerprint.of("payload");

        UUID result1 = UUID.randomUUID();
        UUID result2 = UUID.randomUUID();

        IdempotencyExecutionResult res1 = idempotencyService.execute(
                IdempotencyCommand.of(actor1, operation, key, fp), () -> result1
        );
        IdempotencyExecutionResult res2 = idempotencyService.execute(
                IdempotencyCommand.of(actor2, operation, key, fp), () -> result2
        );

        assertThat(res1.replayed()).isFalse();
        assertThat(res2.replayed()).isFalse();
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(actor1, operation, key)).isPresent();
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(actor2, operation, key)).isPresent();
    }

    @Test
    @DisplayName("Concurrent identical requests execute the underlying operation exactly once")
    void concurrentIdenticalRequests() throws Exception {
        UUID actorId = createTestUser();
        LedgerAccount reserve = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount clearing = createSystemAccount(AccountType.PSP_CLEARING);

        String key = "key-concurrent-" + UUID.randomUUID();
        String operation = "CONCURRENT_POST";
        RequestFingerprint fp = RequestFingerprint.of("concurrent-payload");
        IdempotencyCommand command = IdempotencyCommand.of(actorId, operation, key, fp);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger invocations = new AtomicInteger(0);

        List<Future<IdempotencyExecutionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return idempotencyService.execute(command, () -> {
                    invocations.incrementAndGet();
                    PostingResult postingResult = ledgerPostingService.post(PostJournalCommand.of(
                            PostingLine.debit(reserve.getId(), 25000L),
                            PostingLine.credit(clearing.getId(), 25000L)
                    ));
                    return postingResult.journalTransactionId();
                });
            }));
        }

        List<IdempotencyExecutionResult> results = new ArrayList<>();
        for (Future<IdempotencyExecutionResult> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        // Exactly one underlying execution occurred
        assertThat(invocations.get()).isEqualTo(1);

        // All callers received the same result ID
        UUID expectedResultId = results.get(0).resultId();
        assertThat(results).allMatch(r -> r.resultId().equals(expectedResultId));

        // Exactly one result had replayed=false, remaining had replayed=true
        long executedCount = results.stream().filter(r -> !r.replayed()).count();
        long replayedCount = results.stream().filter(IdempotencyExecutionResult::replayed).count();
        assertThat(executedCount).isEqualTo(1);
        assertThat(replayedCount).isEqualTo(threadCount - 1);

        // Exactly one idempotency record exists in COMPLETED status
        IdempotencyRecord record = idempotencyRecordRepository
                .findByActorUserIdAndOperationAndIdempotencyKey(actorId, operation, key)
                .orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);

        // Financial snapshot updated exactly once
        assertThat(getSnapshotBalance(reserve.getId())).isEqualTo(25000L);
        assertThat(getSnapshotBalance(clearing.getId())).isEqualTo(-25000L);
    }

    @Test
    @DisplayName("Concurrent requests with conflicting fingerprints allow winner to execute and reject loser")
    void concurrentConflictingFingerprints() throws Exception {
        UUID actorId = createTestUser();
        String key = "key-race-conflict-" + UUID.randomUUID();
        String operation = "TRANSFER";

        RequestFingerprint fp1 = RequestFingerprint.of("fp1");
        RequestFingerprint fp2 = RequestFingerprint.of("fp2");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<?> f1 = executor.submit(() -> {
            barrier.await();
            return idempotencyService.execute(IdempotencyCommand.of(actorId, operation, key, fp1), UUID::randomUUID);
        });

        Future<?> f2 = executor.submit(() -> {
            barrier.await();
            return idempotencyService.execute(IdempotencyCommand.of(actorId, operation, key, fp2), UUID::randomUUID);
        });

        int successCount = 0;
        int conflictCount = 0;

        try {
            f1.get();
            successCount++;
        } catch (Exception e) {
            if (e.getCause() instanceof IdempotencyConflictException) conflictCount++;
        }

        try {
            f2.get();
            successCount++;
        } catch (Exception e) {
            if (e.getCause() instanceof IdempotencyConflictException) conflictCount++;
        }

        executor.shutdown();

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent winner rollback allows concurrently waiting loser to acquire slot and execute")
    void concurrentWinnerRollbackWaitingLoserExecutesSuccessfully() throws Exception {
        UUID actorId = createTestUser();
        LedgerAccount reserve = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount clearing = createSystemAccount(AccountType.PSP_CLEARING);

        String key = "key-winner-rollback-race-" + UUID.randomUUID();
        String operation = "ROLLBACK_RACE_POST";
        RequestFingerprint fp = RequestFingerprint.of("payload-rollback-race");
        IdempotencyCommand command = IdempotencyCommand.of(actorId, operation, key, fp);

        java.util.concurrent.CountDownLatch aClaimedSlotLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch bAttemptedLatch = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger aInvocations = new AtomicInteger(0);
        AtomicInteger bInvocations = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Caller A: claims slot, waits for B to attempt claim, then fails
        Future<IdempotencyExecutionResult> futureA = executor.submit(() -> {
            return idempotencyService.execute(command, () -> {
                aInvocations.incrementAndGet();
                aClaimedSlotLatch.countDown();
                try {
                    bAttemptedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    Thread.sleep(100); // Ensure B is blocked inside PostgreSQL coordination
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("Simulated failure in Caller A callback");
            });
        });

        // Caller B: starts after A claims, attempts claim while A is uncommitted, executes after A rolls back
        Future<IdempotencyExecutionResult> futureB = executor.submit(() -> {
            aClaimedSlotLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            bAttemptedLatch.countDown();
            return idempotencyService.execute(command, () -> {
                bInvocations.incrementAndGet();
                PostingResult postingResult = ledgerPostingService.post(PostJournalCommand.of(
                        PostingLine.debit(reserve.getId(), 15000L),
                        PostingLine.credit(clearing.getId(), 15000L)
                ));
                return postingResult.journalTransactionId();
            });
        });

        // Verify A failed
        assertThatThrownBy(futureA::get)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated failure in Caller A callback");

        // Verify B succeeded with replayed=false
        IdempotencyExecutionResult resultB = futureB.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(resultB.replayed()).isFalse();
        assertThat(resultB.resultId()).isNotNull();

        executor.shutdown();

        // Callback invocation counts
        assertThat(aInvocations.get()).isEqualTo(1);
        assertThat(bInvocations.get()).isEqualTo(1);

        // Exactly 1 COMPLETED idempotency record in DB
        IdempotencyRecord record = idempotencyRecordRepository
                .findByActorUserIdAndOperationAndIdempotencyKey(actorId, operation, key)
                .orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getResultId()).isEqualTo(resultB.resultId());

        // Exactly 1 journal transaction posted and snapshot updated
        assertThat(journalTransactionRepository.findById(resultB.resultId())).isPresent();
        assertThat(getSnapshotBalance(reserve.getId())).isEqualTo(15000L);
        assertThat(getSnapshotBalance(clearing.getId())).isEqualTo(-15000L);
    }

    @Test
    @DisplayName("Failure in underlying operation rolls back idempotency record allowing subsequent retry")
    void failureRollbackAllowsRetry() {
        UUID actorId = createTestUser();
        String key = "key-fail-retry-" + UUID.randomUUID();
        String operation = "TEST_FAIL";
        RequestFingerprint fp = RequestFingerprint.of("payload-fail");
        IdempotencyCommand command = IdempotencyCommand.of(actorId, operation, key, fp);

        // First attempt throws RuntimeException
        assertThatThrownBy(() -> idempotencyService.execute(command, () -> {
            throw new RuntimeException("Simulated financial failure");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated financial failure");

        // Verify no idempotency record remains committed
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(actorId, operation, key))
                .isEmpty();

        // Retry with successful execution
        UUID successId = UUID.randomUUID();
        IdempotencyExecutionResult retryResult = idempotencyService.execute(command, () -> successId);

        assertThat(retryResult.replayed()).isFalse();
        assertThat(retryResult.resultId()).isEqualTo(successId);
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(actorId, operation, key))
                .isPresent();
    }

    @Test
    @DisplayName("Callback returning null result ID is rejected and transaction rolls back")
    void nullResultRejected() {
        UUID actorId = createTestUser();
        String key = "key-null-res-" + UUID.randomUUID();
        String operation = "TEST_NULL";
        RequestFingerprint fp = RequestFingerprint.of("payload-null");
        IdempotencyCommand command = IdempotencyCommand.of(actorId, operation, key, fp);

        assertThatThrownBy(() -> idempotencyService.execute(command, () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned null result ID");

        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(actorId, operation, key))
                .isEmpty();
    }

    private Long getSnapshotBalance(UUID ledgerAccountId) {
        return ledgerBalanceSnapshotRepository.findById(ledgerAccountId)
                .map(s -> s.getBalanceMinor())
                .orElse(null);
    }

    private LedgerAccount createSystemAccount(AccountType type) {
        LedgerAccount account = LedgerAccount.createSystemAccount(type);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private UUID createTestUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'CUSTOMER', 'ACTIVE', ?, ?)",
                id, "idemp_svc_test." + id + "@example.com", "$2a$10$dummyHashValueForTestingPurposeOnly", now, now
        );
        return id;
    }
}
