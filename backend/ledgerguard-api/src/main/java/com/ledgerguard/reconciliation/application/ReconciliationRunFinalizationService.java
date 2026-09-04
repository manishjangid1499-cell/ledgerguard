package com.ledgerguard.reconciliation.application;

import com.ledgerguard.reconciliation.domain.ReconciliationRun;
import com.ledgerguard.reconciliation.domain.ReconciliationRunStatus;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Finalizes a reconciliation run in a dedicated REQUIRES_NEW transaction.
 * <p>
 * Correct locking sequence:
 * <ol>
 *   <li>Acquire {@code FOR UPDATE} (exclusive row lock) on the run row — blocks concurrent item inserts
 *       that hold {@code FOR SHARE} on the same row.</li>
 *   <li>Verify run is still RUNNING.</li>
 *   <li>While holding the lock, count DISCREPANCY and UNRESOLVED items from the DB.</li>
 *   <li>Transition run to COMPLETED or FAILED with exact counters and timestamps.</li>
 *   <li>COMMIT — releases the lock; any waiting item inserts then see terminal status and are rejected.</li>
 * </ol>
 * Because item counters are derived from persisted rows counted while holding FOR UPDATE,
 * the terminal run counters are guaranteed to equal the immutable item set.
 */
@Service
public class ReconciliationRunFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunFinalizationService.class);

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationItemRepository itemRepository;

    public ReconciliationRunFinalizationService(ReconciliationRunRepository runRepository,
                                                ReconciliationItemRepository itemRepository) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRun(UUID runId, long journalsChecked, long accountsChecked, long operationsChecked) {
        finalizeRun(runId, journalsChecked, accountsChecked, operationsChecked, false, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(UUID runId, long journalsChecked, long accountsChecked, long operationsChecked,
                        String failureReason) {
        finalizeRun(runId, journalsChecked, accountsChecked, operationsChecked, true, failureReason);
    }

    private void finalizeRun(UUID runId, long journalsChecked, long accountsChecked, long operationsChecked,
                              boolean failed, String failureReason) {
        // Step 1: Acquire FOR UPDATE on the run row — serializes with item inserts (FOR SHARE)
        ReconciliationRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "ReconciliationRun not found during finalization: " + runId));

        // Step 2: Defensive check (DB trigger also enforces this)
        if (run.getStatus() != ReconciliationRunStatus.RUNNING) {
            throw new IllegalStateException(
                    "Cannot finalize reconciliation_run " + runId + ": already in status " + run.getStatus());
        }

        // Steps 3 & 4: Count items from DB while holding FOR UPDATE
        long discrepancyCount = itemRepository.countDiscrepanciesByRunId(runId);
        long unresolvedCount = itemRepository.countUnresolvedByRunId(runId);

        // Step 5: Transition
        if (failed) {
            run.fail(journalsChecked, accountsChecked, operationsChecked,
                    discrepancyCount, unresolvedCount, failureReason);
            log.warn("Reconciliation run {} FAILED: {} — discrepancies={}, unresolved={}",
                    runId, failureReason, discrepancyCount, unresolvedCount);
        } else {
            run.complete(journalsChecked, accountsChecked, operationsChecked,
                    discrepancyCount, unresolvedCount);
            log.info("Reconciliation run {} COMPLETED: journals={}, accounts={}, operations={}, discrepancies={}, unresolved={}",
                    runId, journalsChecked, accountsChecked, operationsChecked, discrepancyCount, unresolvedCount);
        }

        runRepository.save(run);
        // Step 6: COMMIT (happens when @Transactional method returns)
    }
}
