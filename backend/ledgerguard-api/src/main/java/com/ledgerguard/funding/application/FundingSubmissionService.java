package com.ledgerguard.funding.application;

import com.ledgerguard.common.application.SubmissionPreparationResult;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Executes an atomic submission claim transaction for FundingOperation.
 * <p>
 * Ensures that exactly one caller receives submissionClaimed=true and is authorized to execute
 * the outbound provider POST outside database transactions.
 */
@Service
public class FundingSubmissionService {

    private final FundingOperationRepository fundingOperationRepository;
    private final EntityManager entityManager;

    public FundingSubmissionService(
            FundingOperationRepository fundingOperationRepository,
            EntityManager entityManager
    ) {
        this.fundingOperationRepository = fundingOperationRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionPreparationResult<FundingOperation> claimSubmission(UUID fundingId, Instant nextPollAt) {
        Objects.requireNonNull(fundingId, "fundingId must not be null");
        Objects.requireNonNull(nextPollAt, "nextPollAt must not be null");

        FundingOperation funding = fundingOperationRepository.findByIdForUpdate(fundingId)
                .orElseThrow(() -> new IllegalStateException("FundingOperation not found: " + fundingId));
        entityManager.refresh(funding);

        if (funding.getStatus() == FundingStatus.CREATED) {
            funding.prepareSubmission(nextPollAt);
            fundingOperationRepository.saveAndFlush(funding);
            return new SubmissionPreparationResult<>(funding, true);
        }

        return new SubmissionPreparationResult<>(funding, false);
    }
}
