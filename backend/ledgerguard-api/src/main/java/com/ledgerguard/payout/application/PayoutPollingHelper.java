package com.ledgerguard.payout.application;

import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transactional helper for payout status recovery polling.
 */
@Component
public class PayoutPollingHelper {

    private static final Logger log = LoggerFactory.getLogger(PayoutPollingHelper.class);

    private final PayoutRepository payoutRepository;
    private final EntityManager entityManager;

    public PayoutPollingHelper(
            PayoutRepository payoutRepository,
            EntityManager entityManager
    ) {
        this.payoutRepository = payoutRepository;
        this.entityManager = entityManager;
    }

    /**
     * Step 0: Exhaustion finalizer in a short transaction.
     * Transitions any rows that reached max attempts and are due to RECONCILIATION_REQUIRED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int finalizeExhausted(Instant now, int maxAttempts, int batchSize) {
        List<UUID> candidateIds = payoutRepository.findExhaustedCandidateIdsForUpdate(now, maxAttempts, batchSize);
        for (UUID id : candidateIds) {
            Payout payout = payoutRepository.findById(id).orElse(null);
            if (payout != null && (payout.getStatus().name().equals("PROCESSING") || payout.getStatus().name().equals("UNKNOWN"))) {
                payout.markReconciliationRequired();
                payoutRepository.saveAndFlush(payout);
                log.warn("Exhaustion finalizer moved Payout {} to RECONCILIATION_REQUIRED (attempts={})",
                        id, payout.getProviderPollAttempts());
            }
        }
        return candidateIds.size();
    }

    /**
     * Step A: Claim due rows for polling in a short transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedPollItem> claimDue(Instant now, int maxAttempts, int retryDelaySeconds, int batchSize) {
        List<UUID> candidateIds = payoutRepository.findDueCandidateIdsForUpdate(now, maxAttempts, batchSize);
        List<ClaimedPollItem> claimed = new ArrayList<>();

        for (UUID id : candidateIds) {
            Payout payout = payoutRepository.findById(id).orElse(null);
            if (payout != null) {
                payout.incrementPollAttempts(now.plusSeconds(retryDelaySeconds));
                payoutRepository.saveAndFlush(payout);
                claimed.add(new ClaimedPollItem(id, payout.getProviderPollAttempts()));
            }
        }
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReconciliationRequiredIfAttemptsExhausted(UUID id, int maxAttempts) {
        Payout payout = payoutRepository.findByIdForUpdate(id).orElse(null);
        if (payout != null) {
            entityManager.refresh(payout);
            if (payout.getProviderPollAttempts() >= maxAttempts
                    && (payout.getStatus().name().equals("PROCESSING") || payout.getStatus().name().equals("UNKNOWN"))) {
                payout.markReconciliationRequired();
                payoutRepository.saveAndFlush(payout);
                log.warn("Payout {} exhausted max attempts ({}); moved to RECONCILIATION_REQUIRED",
                        id, payout.getProviderPollAttempts());
            }
        }
    }

    public record ClaimedPollItem(UUID id, int attemptCount) {}
}
