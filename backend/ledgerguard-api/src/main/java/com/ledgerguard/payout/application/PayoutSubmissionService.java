package com.ledgerguard.payout.application;

import com.ledgerguard.common.application.SubmissionPreparationResult;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
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
 * Executes an atomic submission claim transaction for Payout.
 * <p>
 * Ensures that exactly one caller receives submissionClaimed=true and is authorized to execute
 * the outbound provider POST outside database transactions.
 * If the linked hold is already EXPIRED while in CREATED state, fails locally with submissionClaimed=false.
 */
@Service
public class PayoutSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(PayoutSubmissionService.class);

    private final PayoutRepository payoutRepository;
    private final BalanceHoldRepository balanceHoldRepository;
    private final EntityManager entityManager;

    public PayoutSubmissionService(
            PayoutRepository payoutRepository,
            BalanceHoldRepository balanceHoldRepository,
            EntityManager entityManager
    ) {
        this.payoutRepository = payoutRepository;
        this.balanceHoldRepository = balanceHoldRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SubmissionPreparationResult<Payout> claimSubmission(UUID payoutId, Instant nextPollAt) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        Objects.requireNonNull(nextPollAt, "nextPollAt must not be null");

        Payout payout = payoutRepository.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new IllegalStateException("Payout not found: " + payoutId));
        entityManager.refresh(payout);

        if (payout.getStatus() == PayoutStatus.CREATED) {
            BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId())
                    .orElseThrow(() -> new IllegalStateException("Linked balance hold not found: " + payout.getBalanceHoldId()));

            if (hold.getStatus() == HoldStatus.EXPIRED) {
                log.info("Payout {} hold {} is EXPIRED in CREATED state. Transitioning CREATED -> FAILED locally.",
                        payoutId, hold.getId());
                payout.markFailed(Instant.now(), null);
                payoutRepository.saveAndFlush(payout);
                return new SubmissionPreparationResult<>(payout, false);
            }

            if (hold.getStatus() != HoldStatus.ACTIVE) {
                throw new IllegalStateException("Cannot prepare submission for Payout " + payoutId
                        + " because linked hold " + hold.getId() + " is " + hold.getStatus());
            }

            payout.prepareSubmission(nextPollAt);
            payoutRepository.saveAndFlush(payout);
            return new SubmissionPreparationResult<>(payout, true);
        }

        return new SubmissionPreparationResult<>(payout, false);
    }
}
