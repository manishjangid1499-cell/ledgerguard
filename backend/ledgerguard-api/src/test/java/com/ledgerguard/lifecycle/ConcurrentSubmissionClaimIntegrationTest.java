package com.ledgerguard.lifecycle;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.common.application.SubmissionPreparationResult;
import com.ledgerguard.funding.application.FundingSubmissionService;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentSubmissionClaimIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private FundingSubmissionService fundingSubmissionService;

    @Autowired
    private FundingOperationRepository fundingOperationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID customerAccountId;

    @BeforeEach
    void setUp() {
        Timestamp now = Timestamp.from(Instant.now());
        userId = UUID.randomUUID();
        customerAccountId = UUID.randomUUID();

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
    }

    @Test
    @DisplayName("Concurrent threads attempting submission claim on same CREATED operation yield exactly one winner")
    void concurrentSubmissionClaimYieldsExactlyOneWinner() throws Exception {
        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger claimedCount = new AtomicInteger(0);
        AtomicInteger notClaimedCount = new AtomicInteger(0);

        List<Future<?>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    SubmissionPreparationResult<FundingOperation> result =
                            fundingSubmissionService.claimSubmission(fundingId, Instant.now().plusSeconds(10));
                    if (result.submissionClaimed()) {
                        claimedCount.incrementAndGet();
                    } else {
                        notClaimedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(claimedCount.get()).isEqualTo(1);
        assertThat(notClaimedCount.get()).isEqualTo(threads - 1);

        FundingOperation finalFunding = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(finalFunding.getStatus()).isEqualTo(FundingStatus.PROCESSING);
        assertThat(finalFunding.getNextProviderPollAt()).isNotNull();
    }
}
