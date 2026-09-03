package com.ledgerguard.funding.application;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
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
 * Transactional helper for funding operations status recovery polling.
 */
@Component
public class FundingPollingHelper {

    private static final Logger log = LoggerFactory.getLogger(FundingPollingHelper.class);

    private final FundingOperationRepository fundingOperationRepository;
    private final EntityManager entityManager;

    public FundingPollingHelper(
            FundingOperationRepository fundingOperationRepository,
            EntityManager entityManager
    ) {
        this.fundingOperationRepository = fundingOperationRepository;
        this.entityManager = entityManager;
    }

    /**
     * Step 0: Exhaustion finalizer in a short transaction.
     * Transitions any rows that reached max attempts and are due to RECONCILIATION_REQUIRED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int finalizeExhausted(Instant now, int maxAttempts, int batchSize) {
        List<UUID> candidateIds = fundingOperationRepository.findExhaustedCandidateIdsForUpdate(now, maxAttempts, batchSize);
        for (UUID id : candidateIds) {
            FundingOperation funding = fundingOperationRepository.findById(id).orElse(null);
            if (funding != null && (funding.getStatus().name().equals("PROCESSING") || funding.getStatus().name().equals("UNKNOWN"))) {
                funding.markReconciliationRequired();
                fundingOperationRepository.saveAndFlush(funding);
                log.warn("Exhaustion finalizer moved FundingOperation {} to RECONCILIATION_REQUIRED (attempts={})",
                        id, funding.getProviderPollAttempts());
            }
        }
        return candidateIds.size();
    }

    /**
     * Step A: Claim due rows for polling in a short transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedPollItem> claimDue(Instant now, int maxAttempts, int retryDelaySeconds, int batchSize) {
        List<UUID> candidateIds = fundingOperationRepository.findDueCandidateIdsForUpdate(now, maxAttempts, batchSize);
        List<ClaimedPollItem> claimed = new ArrayList<>();

        for (UUID id : candidateIds) {
            FundingOperation funding = fundingOperationRepository.findById(id).orElse(null);
            if (funding != null) {
                funding.incrementPollAttempts(now.plusSeconds(retryDelaySeconds));
                fundingOperationRepository.saveAndFlush(funding);
                claimed.add(new ClaimedPollItem(id, funding.getProviderPollAttempts()));
            }
        }
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReconciliationRequiredIfAttemptsExhausted(UUID id, int maxAttempts) {
        FundingOperation funding = fundingOperationRepository.findByIdForUpdate(id).orElse(null);
        if (funding != null) {
            entityManager.refresh(funding);
            if (funding.getProviderPollAttempts() >= maxAttempts
                    && (funding.getStatus().name().equals("PROCESSING") || funding.getStatus().name().equals("UNKNOWN"))) {
                funding.markReconciliationRequired();
                fundingOperationRepository.saveAndFlush(funding);
                log.warn("FundingOperation {} exhausted max attempts ({}); moved to RECONCILIATION_REQUIRED",
                        id, funding.getProviderPollAttempts());
            }
        }
    }

    public record ClaimedPollItem(UUID id, int attemptCount) {}
}
