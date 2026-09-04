package com.ledgerguard.reconciliation.application;

import com.ledgerguard.reconciliation.domain.ReconciliationRun;
import com.ledgerguard.reconciliation.domain.ReconciliationTrigger;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the full three-level reconciliation lifecycle for a single run.
 * <p>
 * Flow:
 * <ol>
 *   <li>Persist a RUNNING reconciliation_run.</li>
 *   <li>Level 1 — Journal Balance (JournalBalanceChecker).</li>
 *   <li>Level 2 — Snapshot Consistency (SnapshotConsistencyChecker).</li>
 *   <li>Level 3 — Provider Settlement (ProviderSettlementChecker).</li>
 *   <li>Finalize run to COMPLETED via ReconciliationRunFinalizationService
 *       (FOR UPDATE → count items → transition → commit).</li>
 *   <li>On unrecoverable infrastructure failure: best-effort finalize to FAILED.</li>
 * </ol>
 * <p>
 * DETECTION ONLY — no financial or business table is mutated.
 */
@Service
public class ReconciliationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEngine.class);

    private final ReconciliationRunRepository runRepository;
    private final JournalBalanceChecker journalBalanceChecker;
    private final SnapshotConsistencyChecker snapshotConsistencyChecker;
    private final ProviderSettlementChecker providerSettlementChecker;
    private final ReconciliationRunFinalizationService finalizationService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public ReconciliationEngine(ReconciliationRunRepository runRepository,
                                JournalBalanceChecker journalBalanceChecker,
                                SnapshotConsistencyChecker snapshotConsistencyChecker,
                                ProviderSettlementChecker providerSettlementChecker,
                                ReconciliationRunFinalizationService finalizationService,
                                org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.journalBalanceChecker = journalBalanceChecker;
        this.snapshotConsistencyChecker = snapshotConsistencyChecker;
        this.providerSettlementChecker = providerSettlementChecker;
        this.finalizationService = finalizationService;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Executes a full reconciliation run with the given trigger source.
     *
     * @param trigger SCHEDULED or ON_DEMAND
     * @return the UUID of the completed (or failed) reconciliation_run row
     */
    public java.util.UUID run(ReconciliationTrigger trigger) {
        ReconciliationRun run = persistRunning(trigger);
        java.util.UUID runId = run.getId();
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
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception fe) {
                log.error("Failed to persist FAILED status for reconciliation run {}: {}", runId, fe.getMessage(), fe);
            }
        }

        return runId;
    }

    public ReconciliationRun persistRunning(ReconciliationTrigger trigger) {
        return transactionTemplate.execute(status -> {
            ReconciliationRun run = ReconciliationRun.start(trigger);
            return runRepository.saveAndFlush(run);
        });
    }
}
