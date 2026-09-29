package com.ledgerguard.reconciliation.application;

import com.ledgerguard.reconciliation.domain.ReconciliationConflictException;
import com.ledgerguard.reconciliation.domain.ReconciliationRun;
import com.ledgerguard.reconciliation.domain.ReconciliationRunStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationTrigger;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Orchestrates the full three-level reconciliation lifecycle for a single run
 * under an exclusive PostgreSQL session-scoped advisory lock.
 * <p>
 * <b>Advisory Locking Architecture:</b>
 * <ul>
 *   <li>Acquires a session-scoped PostgreSQL advisory lock using {@code SELECT pg_try_advisory_lock(?)}
 *       with the authoritative constant {@link #RECONCILIATION_LOCK_ID} (0x4C475F5245434F4EL = 5496466677182975822L).</li>
 *   <li>The dedicated lock {@link Connection} is kept in {@code autoCommit = true} mode for the entire
 *       reconciliation run. Because autoCommit is enabled and no Spring transaction wraps this method,
 *       no backend PostgreSQL transaction block remains open during execution (including Level 3 external
 *       PSP HTTP calls).</li>
 *   <li>All database mutations (orphan recovery, run persistence, item creation, cases, finalization)
 *       execute within separate short-lived Spring-managed transactions on separate connections.</li>
 *   <li>The session lock is explicitly released in a {@code finally} block via {@code SELECT pg_advisory_unlock(?)}.
 *       If unlocking fails or returns false, the physical connection is aborted via {@link Connection#abort}
 *       to guarantee that an unreleased lock is discarded and never returned to the connection pool.</li>
 * </ul>
 * <p>
 * <b>Connection Pool Sizing Impact:</b>
 * One connection from the {@link DataSource} pool is held exclusively for the duration of the entire
 * reconciliation run (typically 200ms - 2s). The DataSource connection pool (HikariCP {@code maximumPoolSize})
 * must be sized to accommodate this dedicated lock connection alongside concurrent user API requests and
 * worker tasks without pool exhaustion.
 * <p>
 * Flow:
 * <ol>
 *   <li>Acquire PostgreSQL session advisory lock (0x4C475F5245434F4EL / 5496466677182975822L).</li>
 *   <li>Recover any orphaned RUNNING runs from crashed processes to FAILED (short transaction).</li>
 *   <li>Persist a RUNNING reconciliation_run (short transaction).</li>
 *   <li>Level 1 — Journal Balance (JournalBalanceChecker).</li>
 *   <li>Level 2 — Snapshot Consistency (SnapshotConsistencyChecker).</li>
 *   <li>Level 3 — Provider Settlement (ProviderSettlementChecker).</li>
 *   <li>Finalize run to COMPLETED via ReconciliationRunFinalizationService
 *       (FOR UPDATE &rarr; count items &rarr; transition &rarr; commit).</li>
 *   <li>On unrecoverable failure: best-effort finalize to FAILED.</li>
 *   <li>Finally: Release session advisory lock and close/abort connection.</li>
 * </ol>
 * <p>
 * DETECTION ONLY — no financial or business table is mutated.
 */
@Service
public class ReconciliationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEngine.class);

    /**
     * PostgreSQL 64-bit advisory lock identifier for global reconciliation serialization.
     * Evaluates to 5496466677182975822L (ASCII hex for "LG_RECON").
     * Authoritative single constant for advisory lock acquisition and verification.
     */
    public static final long RECONCILIATION_LOCK_ID = 0x4C475F5245434F4EL;

    private final DataSource dataSource;
    private final ReconciliationRunRepository runRepository;
    private final JournalBalanceChecker journalBalanceChecker;
    private final SnapshotConsistencyChecker snapshotConsistencyChecker;
    private final ProviderSettlementChecker providerSettlementChecker;
    private final ReconciliationRunFinalizationService finalizationService;
    private final TransactionTemplate innerTransactionTemplate;

    public ReconciliationEngine(DataSource dataSource,
                                ReconciliationRunRepository runRepository,
                                JournalBalanceChecker journalBalanceChecker,
                                SnapshotConsistencyChecker snapshotConsistencyChecker,
                                ProviderSettlementChecker providerSettlementChecker,
                                ReconciliationRunFinalizationService finalizationService,
                                PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.runRepository = runRepository;
        this.journalBalanceChecker = journalBalanceChecker;
        this.snapshotConsistencyChecker = snapshotConsistencyChecker;
        this.providerSettlementChecker = providerSettlementChecker;
        this.finalizationService = finalizationService;
        this.innerTransactionTemplate = new TransactionTemplate(transactionManager);
        this.innerTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Executes a full reconciliation run with the given trigger source under an exclusive
     * session-scoped PostgreSQL advisory lock.
     *
     * @param trigger SCHEDULED or ON_DEMAND
     * @return the UUID of the completed (or failed) reconciliation_run row
     * @throws ReconciliationConflictException if another reconciliation is currently running
     */
    public UUID run(ReconciliationTrigger trigger) {
        Connection lockConn = null;
        boolean lockAcquired = false;
        try {
            lockConn = dataSource.getConnection();
            lockConn.setAutoCommit(true);

            try (PreparedStatement stmt = lockConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                stmt.setLong(1, RECONCILIATION_LOCK_ID);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean(1)) {
                        log.warn("Reconciliation session lock 0x{:X} ({} decimal) could not be acquired; another run is already in progress",
                                RECONCILIATION_LOCK_ID, RECONCILIATION_LOCK_ID);
                        throw new ReconciliationConflictException("Reconciliation is already running");
                    }
                }
            }

            lockAcquired = true;
            log.info("Acquired PostgreSQL session advisory lock 0x{:X} ({} decimal) for reconciliation (connection held with autoCommit=true)",
                    RECONCILIATION_LOCK_ID, RECONCILIATION_LOCK_ID);

            recoverOrphanedRuns();
            return executeRun(trigger);

        } catch (SQLException e) {
            log.error("Database error while managing reconciliation lock: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to manage reconciliation lock", e);
        } finally {
            if (lockConn != null) {
                if (lockAcquired) {
                    boolean unlockSucceeded = false;
                    try (PreparedStatement unlockStmt = lockConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        unlockStmt.setLong(1, RECONCILIATION_LOCK_ID);
                        try (ResultSet rs = unlockStmt.executeQuery()) {
                            if (rs.next() && rs.getBoolean(1)) {
                                unlockSucceeded = true;
                                log.info("Released PostgreSQL session advisory lock 0x{:X}", RECONCILIATION_LOCK_ID);
                            } else {
                                log.error("pg_advisory_unlock returned false for lock 0x{:X}; session lock was not held or failed to release",
                                        RECONCILIATION_LOCK_ID);
                            }
                        }
                    } catch (Exception unlockEx) {
                        log.error("Failed to release advisory lock 0x{:X}: {}", RECONCILIATION_LOCK_ID, unlockEx.getMessage(), unlockEx);
                    }

                    try {
                        if (!unlockSucceeded) {
                            try {
                                lockConn.abort(Runnable::run);
                            } catch (Throwable abortEx) {
                                log.warn("Failed to abort lock connection: {}", abortEx.getMessage());
                                lockConn.close();
                            }
                        } else {
                            lockConn.close();
                        }
                    } catch (Exception closeEx) {
                        log.warn("Error closing reconciliation lock connection: {}", closeEx.getMessage());
                    }
                } else {
                    try {
                        lockConn.close();
                    } catch (Exception closeEx) {
                        log.warn("Error closing unacquired lock connection: {}", closeEx.getMessage());
                    }
                }
            }
        }
    }

    private void recoverOrphanedRuns() {
        innerTransactionTemplate.execute(status -> {
            List<ReconciliationRun> orphanedRuns = runRepository.findByStatus(ReconciliationRunStatus.RUNNING);
            for (ReconciliationRun orphan : orphanedRuns) {
                log.warn("Found orphaned reconciliation run {} in RUNNING status; recovering to FAILED", orphan.getId());
                orphan.fail(
                        orphan.getJournalsChecked(),
                        orphan.getAccountsChecked(),
                        orphan.getOperationsChecked(),
                        orphan.getDiscrepancyCount(),
                        orphan.getUnresolvedCount(),
                        "Reconciliation run aborted or process terminated unexpectedly (orphaned run recovered)"
                );
                runRepository.saveAndFlush(orphan);
            }
            return null;
        });
    }

    private UUID executeRun(ReconciliationTrigger trigger) {
        ReconciliationRun run = persistRunning(trigger);
        UUID runId = run.getId();
        log.info("Reconciliation run {} started (trigger={})", runId, trigger);

        long journalsChecked = 0;
        long accountsChecked = 0;
        long operationsChecked = 0;

        try {
            journalsChecked = journalBalanceChecker.check(runId);
            accountsChecked = snapshotConsistencyChecker.check(runId);
            operationsChecked = providerSettlementChecker.check(runId);

            finalizationService.completeRun(runId, journalsChecked, accountsChecked, operationsChecked);
            log.info("Reconciliation run {} COMPLETED", runId);

        } catch (Exception e) {
            log.error("Reconciliation run {} FAILED: {}", runId, e.getMessage(), e);
            try {
                finalizationService.failRun(runId, journalsChecked, accountsChecked, operationsChecked,
                        "Reconciliation execution encountered an internal error during verification sweeps. Check service logs for details.");
            } catch (Exception fe) {
                log.error("Failed to persist FAILED status for reconciliation run {}: {}", runId, fe.getMessage(), fe);
            }
        }

        return runId;
    }

    public ReconciliationRun persistRunning(ReconciliationTrigger trigger) {
        return innerTransactionTemplate.execute(status -> {
            ReconciliationRun run = ReconciliationRun.start(trigger);
            return runRepository.saveAndFlush(run);
        });
    }
}
