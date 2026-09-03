package com.ledgerguard.funding.application;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional transition helper for FundingOperation status recovery updates.
 */
@Service
public class FundingTransitionService {

    private static final Logger log = LoggerFactory.getLogger(FundingTransitionService.class);

    private final FundingOperationRepository fundingOperationRepository;
    private final EntityManager entityManager;

    public FundingTransitionService(
            FundingOperationRepository fundingOperationRepository,
            EntityManager entityManager
    ) {
        this.fundingOperationRepository = fundingOperationRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FundingOperation markUnknown(UUID fundingId, Instant now, Instant nextPollAt) {
        Objects.requireNonNull(fundingId, "fundingId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(nextPollAt, "nextPollAt must not be null");

        FundingOperation funding = fundingOperationRepository.findByIdForUpdate(fundingId)
                .orElseThrow(() -> new IllegalStateException("FundingOperation not found: " + fundingId));
        entityManager.refresh(funding);

        if (funding.getStatus() == FundingStatus.PROCESSING || funding.getStatus() == FundingStatus.UNKNOWN) {
            funding.markUnknown(now, nextPollAt);
            fundingOperationRepository.saveAndFlush(funding);
            log.info("Transitioned FundingOperation {} to UNKNOWN (unknownSince={}, nextPoll={})",
                    fundingId, funding.getUnknownSince(), nextPollAt);
        }
        return funding;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FundingOperation markProcessing(UUID fundingId, Instant nextPollAt) {
        Objects.requireNonNull(fundingId, "fundingId must not be null");
        Objects.requireNonNull(nextPollAt, "nextPollAt must not be null");

        FundingOperation funding = fundingOperationRepository.findByIdForUpdate(fundingId)
                .orElseThrow(() -> new IllegalStateException("FundingOperation not found: " + fundingId));
        entityManager.refresh(funding);

        if (funding.getStatus() == FundingStatus.PROCESSING || funding.getStatus() == FundingStatus.UNKNOWN) {
            funding.markProcessing(nextPollAt);
            fundingOperationRepository.saveAndFlush(funding);
            log.info("Transitioned/retained FundingOperation {} as PROCESSING (nextPoll={})", fundingId, nextPollAt);
        }
        return funding;
    }
}
