package com.ledgerguard.funding.application;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.provider.application.ProviderEventConflictException;
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
 * Handles terminal failure transitions for FundingOperation across local pre-provider failures,
 * definite pre-acceptance rejections, and authoritative provider failures.
 */
@Service
public class FundingFailureService {

    private static final Logger log = LoggerFactory.getLogger(FundingFailureService.class);

    private final FundingOperationRepository fundingOperationRepository;
    private final EntityManager entityManager;

    public FundingFailureService(
            FundingOperationRepository fundingOperationRepository,
            EntityManager entityManager
    ) {
        this.fundingOperationRepository = fundingOperationRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public FundingOperation failFunding(UUID fundingId, UUID providerOperationId, Instant completedAt) {
        Objects.requireNonNull(fundingId, "fundingId must not be null");
        Instant finalCompletedAt = completedAt != null ? completedAt : Instant.now();

        FundingOperation funding = fundingOperationRepository.findByIdForUpdate(fundingId)
                .orElseThrow(() -> new IllegalStateException("FundingOperation not found: " + fundingId));
        entityManager.refresh(funding);

        if (funding.getStatus() == FundingStatus.FAILED) {
            if (providerOperationId != null && funding.getProviderOperationId() != null
                    && !funding.getProviderOperationId().equals(providerOperationId)) {
                throw new ProviderEventConflictException("Conflicting providerOperationId " + providerOperationId
                        + " for already FAILED FundingOperation " + fundingId + " with ID " + funding.getProviderOperationId());
            }
            log.info("FundingOperation {} is already in terminal status FAILED. Idempotent return.", fundingId);
            return funding;
        }

        if (funding.getStatus() == FundingStatus.SUCCEEDED) {
            throw new ProviderEventConflictException("FundingOperation " + fundingId
                    + " is in terminal status SUCCEEDED and cannot be transitioned to FAILED");
        }

        funding.markFailed(finalCompletedAt, providerOperationId);
        fundingOperationRepository.saveAndFlush(funding);
        log.info("Transitioned FundingOperation {} to FAILED: providerOperationId={}", fundingId, providerOperationId);
        return funding;
    }
}
