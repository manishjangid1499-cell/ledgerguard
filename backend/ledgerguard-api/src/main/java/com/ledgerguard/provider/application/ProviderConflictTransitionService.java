package com.ledgerguard.provider.application;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Dedicated transactional boundary to durably persist RECONCILIATION_REQUIRED for nonterminal
 * operations encountering contradictory provider identity or stream conflict.
 * <p>
 * Uses REQUIRES_NEW propagation to ensure that the status update commits to PostgreSQL
 * before the caller surfaces an HTTP 409 Conflict exception.
 */
@Service
public class ProviderConflictTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ProviderConflictTransitionService.class);

    private final FundingOperationRepository fundingOperationRepository;
    private final PayoutRepository payoutRepository;
    private final EntityManager entityManager;

    public ProviderConflictTransitionService(
            FundingOperationRepository fundingOperationRepository,
            PayoutRepository payoutRepository,
            EntityManager entityManager
    ) {
        this.fundingOperationRepository = fundingOperationRepository;
        this.payoutRepository = payoutRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionFundingToReconciliationRequired(UUID fundingId) {
        Objects.requireNonNull(fundingId, "fundingId must not be null");
        fundingOperationRepository.findByIdForUpdate(fundingId).ifPresent(funding -> {
            entityManager.refresh(funding);
            if (funding.getStatus() == FundingStatus.PROCESSING || funding.getStatus() == FundingStatus.UNKNOWN) {
                funding.markReconciliationRequired();
                fundingOperationRepository.saveAndFlush(funding);
                log.warn("Durably transitioned FundingOperation {} to RECONCILIATION_REQUIRED due to conflict", fundingId);
            } else {
                log.info("FundingOperation {} is already in status {}; skipping RECONCILIATION_REQUIRED transition",
                        fundingId, funding.getStatus());
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionPayoutToReconciliationRequired(UUID payoutId) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        payoutRepository.findByIdForUpdate(payoutId).ifPresent(payout -> {
            entityManager.refresh(payout);
            if (payout.getStatus() == PayoutStatus.PROCESSING || payout.getStatus() == PayoutStatus.UNKNOWN) {
                payout.markReconciliationRequired();
                payoutRepository.saveAndFlush(payout);
                log.warn("Durably transitioned Payout {} to RECONCILIATION_REQUIRED due to conflict", payoutId);
            } else {
                log.info("Payout {} is already in status {}; skipping RECONCILIATION_REQUIRED transition",
                        payoutId, payout.getStatus());
            }
        });
    }
}
