package com.ledgerguard.reconciliation.application;

import com.ledgerguard.reconciliation.domain.ReconciliationTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background scheduler that triggers a daily reconciliation run at 02:00 UTC.
 * <p>
 * Delegates entirely to {@link ReconciliationEngine#run(ReconciliationTrigger)}.
 * Tests and internal callers invoke the engine directly with {@link ReconciliationTrigger#ON_DEMAND}.
 */
@Component
public class ScheduledReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReconciliationJob.class);

    private final ReconciliationEngine reconciliationEngine;

    public ScheduledReconciliationJob(ReconciliationEngine reconciliationEngine) {
        this.reconciliationEngine = reconciliationEngine;
    }

    @Scheduled(
            cron = "${ledgerguard.reconciliation.cron:0 0 2 * * *}",
            zone = "UTC"
    )
    public void runScheduled() {
        log.info("Scheduled reconciliation triggered");
        reconciliationEngine.run(ReconciliationTrigger.SCHEDULED);
    }
}
