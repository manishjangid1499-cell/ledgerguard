package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.reconciliation.application.ReconciliationRunFinalizationService;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.domain.ReconciliationRun;
import com.ledgerguard.reconciliation.domain.ReconciliationRunStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationTrigger;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReconciliationRunLifecycleTest — Run lifecycle and finalization concurrency")
class ReconciliationRunLifecycleTest extends AbstractIntegrationTest {

    @Autowired private ReconciliationRunRepository runRepository;
    @Autowired private ReconciliationItemRepository itemRepository;
    @Autowired private ReconciliationRunFinalizationService finalizationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("Run starts RUNNING and successfully completes with exact derived counters")
    void runCompletesWithExactDerivedCounters() {
        ReconciliationRun run = ReconciliationRun.start(ReconciliationTrigger.ON_DEMAND);
        run = runRepository.save(run);
        UUID runId = run.getId();

        // Insert 2 discrepancies and 1 unresolved item
        insertItem(runId, ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.UNBALANCED_JOURNAL, "JOURNAL_TRANSACTION");
        insertItem(runId, ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.SNAPSHOT_MISMATCH, "LEDGER_ACCOUNT");
        insertItem(runId, ReconciliationClassification.UNRESOLVED, ReconciliationProblemType.PROVIDER_UNAVAILABLE, "FUNDING_OPERATION");

        finalizationService.completeRun(runId, 10, 5, 2);

        ReconciliationRun completed = runRepository.findById(runId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getJournalsChecked()).isEqualTo(10);
        assertThat(completed.getAccountsChecked()).isEqualTo(5);
        assertThat(completed.getOperationsChecked()).isEqualTo(2);
        assertThat(completed.getDiscrepancyCount()).isEqualTo(2);
        assertThat(completed.getUnresolvedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Run fails with FAILED status and records failure reason")
    void runFailsWithReason() {
        ReconciliationRun run = ReconciliationRun.start(ReconciliationTrigger.SCHEDULED);
        run = runRepository.save(run);
        UUID runId = run.getId();

        insertItem(runId, ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.UNBALANCED_JOURNAL, "JOURNAL_TRANSACTION");

        finalizationService.failRun(runId, 5, 0, 0, "Simulated infrastructure failure");

        ReconciliationRun failed = runRepository.findById(runId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("Simulated infrastructure failure");
        assertThat(failed.getDiscrepancyCount()).isEqualTo(1);
        assertThat(failed.getUnresolvedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Race A: item INSERT commits first -> finalizer acquires FOR UPDATE -> final counts include item")
    void itemInsertCommitsFirstThenFinalizerCalculatesExactCounters() {
        ReconciliationRun run = ReconciliationRun.start(ReconciliationTrigger.ON_DEMAND);
        run = runRepository.save(run);
        UUID runId = run.getId();

        // Item INSERT commits first in dedicated transaction
        insertItem(runId, ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.SNAPSHOT_MISMATCH, "LEDGER_ACCOUNT");
        insertItem(runId, ReconciliationClassification.UNRESOLVED, ReconciliationProblemType.PROVIDER_UNAVAILABLE, "PAYOUT");

        // Then finalizer runs FOR UPDATE and completes
        finalizationService.completeRun(runId, 10, 5, 2);

        ReconciliationRun completed = runRepository.findById(runId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);

        // Direct SQL checks proving exact equality
        Long sqlDiscrepancies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_items WHERE reconciliation_run_id = ? AND classification = 'DISCREPANCY'",
                Long.class, runId);
        Long sqlUnresolved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_items WHERE reconciliation_run_id = ? AND classification = 'UNRESOLVED'",
                Long.class, runId);

        assertThat(completed.getDiscrepancyCount()).isEqualTo(sqlDiscrepancies).isEqualTo(1);
        assertThat(completed.getUnresolvedCount()).isEqualTo(sqlUnresolved).isEqualTo(1);
    }

    @Test
    @DisplayName("Deterministic concurrency test (Race B): finalizer holds FOR UPDATE -> concurrent item insert waits and is rejected once completed")
    void finalizerBlocksConcurrentItemInsertAndRejectsItAfterTerminalTransition() throws Exception {
        ReconciliationRun run = ReconciliationRun.start(ReconciliationTrigger.ON_DEMAND);
        run = runRepository.save(run);
        UUID runId = run.getId();

        CountDownLatch finalizerLockedLatch = new CountDownLatch(1);
        CountDownLatch insertAttemptStartedLatch = new CountDownLatch(1);
        AtomicBoolean insertFailedWithTerminalError = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Finalizer thread that acquires FOR UPDATE and pauses
        Future<?> finalizerFuture = executor.submit(() -> {
            transactionTemplate.execute(status -> {
                ReconciliationRun r = runRepository.findByIdForUpdate(runId).orElseThrow();
                assertThat(r.getStatus()).isEqualTo(ReconciliationRunStatus.RUNNING);

                // Notify that lock is acquired
                finalizerLockedLatch.countDown();

                // Wait for Thread 2 to start insert attempt
                try {
                    insertAttemptStartedLatch.await(5, TimeUnit.SECONDS);
                    // Small sleep to ensure Thread 2 is actually blocked waiting on the lock
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Complete run
                r.complete(1, 1, 1, 0, 0);
                runRepository.save(r);
                return null;
            });
        });

        // Thread 2: Concurrent insert attempt
        Future<?> insertFuture = executor.submit(() -> {
            try {
                // Wait until Thread 1 has locked the run
                finalizerLockedLatch.await(5, TimeUnit.SECONDS);
                insertAttemptStartedLatch.countDown();

                // Attempt insert while finalizer holds lock or immediately after
                insertItem(runId, ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.SNAPSHOT_MISMATCH, "LEDGER_ACCOUNT");
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("terminal status")) {
                    insertFailedWithTerminalError.set(true);
                } else if (e.getCause() != null && e.getCause().getMessage() != null && e.getCause().getMessage().contains("terminal status")) {
                    insertFailedWithTerminalError.set(true);
                }
            }
        });

        finalizerFuture.get(10, TimeUnit.SECONDS);
        insertFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(insertFailedWithTerminalError.get()).isTrue();

        ReconciliationRun terminalRun = runRepository.findById(runId).orElseThrow();
        assertThat(terminalRun.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);

        // Direct SQL checks proving exact equality
        Long sqlDiscrepancies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_items WHERE reconciliation_run_id = ? AND classification = 'DISCREPANCY'",
                Long.class, runId);
        Long sqlUnresolved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_items WHERE reconciliation_run_id = ? AND classification = 'UNRESOLVED'",
                Long.class, runId);

        assertThat(terminalRun.getDiscrepancyCount()).isEqualTo(sqlDiscrepancies).isEqualTo(0);
        assertThat(terminalRun.getUnresolvedCount()).isEqualTo(sqlUnresolved).isEqualTo(0);
    }

    private void insertItem(UUID runId, ReconciliationClassification classification, ReconciliationProblemType problemType, String entityType) {
        ReconciliationItem item = ReconciliationItem.builder()
                .runId(runId)
                .classification(classification)
                .level(entityType.equals("JOURNAL_TRANSACTION") ? ReconciliationLevel.JOURNAL_BALANCE
                        : entityType.equals("LEDGER_ACCOUNT") ? ReconciliationLevel.SNAPSHOT_CONSISTENCY
                        : ReconciliationLevel.PROVIDER_SETTLEMENT)
                .problemType(problemType)
                .entityType(entityType)
                .entityId(UUID.randomUUID())
                .description("Lifecycle test item")
                .build();
        itemRepository.save(item);
    }
}
