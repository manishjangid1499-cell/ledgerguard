package com.ledgerguard.payout.application;

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
 * Transactional transition helper for Payout status recovery updates.
 */
@Service
public class PayoutTransitionService {

    private static final Logger log = LoggerFactory.getLogger(PayoutTransitionService.class);

    private final PayoutRepository payoutRepository;
    private final EntityManager entityManager;

    public PayoutTransitionService(
            PayoutRepository payoutRepository,
            EntityManager entityManager
    ) {
        this.payoutRepository = payoutRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payout markUnknown(UUID payoutId, Instant now, Instant nextPollAt) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(nextPollAt, "nextPollAt must not be null");

        Payout payout = payoutRepository.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new IllegalStateException("Payout not found: " + payoutId));
        entityManager.refresh(payout);

        if (payout.getStatus() == PayoutStatus.PROCESSING || payout.getStatus() == PayoutStatus.UNKNOWN) {
            payout.markUnknown(now, nextPollAt);
            payoutRepository.saveAndFlush(payout);
            log.info("Transitioned Payout {} to UNKNOWN (unknownSince={}, nextPoll={})",
                    payoutId, payout.getUnknownSince(), nextPollAt);
        }
        return payout;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payout markProcessing(UUID payoutId, Instant nextPollAt) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        Objects.requireNonNull(nextPollAt, "nextPollAt must not be null");

        Payout payout = payoutRepository.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new IllegalStateException("Payout not found: " + payoutId));
        entityManager.refresh(payout);

        if (payout.getStatus() == PayoutStatus.PROCESSING || payout.getStatus() == PayoutStatus.UNKNOWN) {
            payout.markProcessing(nextPollAt);
            payoutRepository.saveAndFlush(payout);
            log.info("Transitioned/retained Payout {} as PROCESSING (nextPoll={})", payoutId, nextPollAt);
        }
        return payout;
    }
}
