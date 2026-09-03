package com.ledgerguard.payout.application;

import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.domain.PayoutValidationException;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
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
 * Transactional service to mark a Payout as FAILED and release its linked BalanceHold
 * upon a definitive pre-acceptance provider failure or authoritative provider failure.
 */
@Service
public class PayoutFailureService {

    private static final Logger log = LoggerFactory.getLogger(PayoutFailureService.class);

    private final PayoutRepository payoutRepository;
    private final BalanceHoldRepository balanceHoldRepository;
    private final EntityManager entityManager;

    public PayoutFailureService(
            PayoutRepository payoutRepository,
            BalanceHoldRepository balanceHoldRepository,
            EntityManager entityManager
    ) {
        this.payoutRepository = payoutRepository;
        this.balanceHoldRepository = balanceHoldRepository;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public PayoutResult failPayout(UUID payoutId) {
        return failPayout(payoutId, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public PayoutResult failPayout(UUID payoutId, UUID providerOperationId, Instant completedAt) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        Instant now = completedAt != null ? completedAt : Instant.now();

        Payout payout = payoutRepository.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new PayoutValidationException("Payout not found: " + payoutId));
        entityManager.refresh(payout);

        if (payout.getStatus() == PayoutStatus.FAILED) {
            if (providerOperationId != null && payout.getProviderOperationId() != null
                    && !payout.getProviderOperationId().equals(providerOperationId)) {
                throw new ProviderEventConflictException("Conflicting providerOperationId " + providerOperationId
                        + " for already FAILED Payout " + payoutId + " with ID " + payout.getProviderOperationId());
            }
            log.info("Payout {} already terminal in status FAILED, returning existing result", payoutId);
            return toResult(payout, false);
        }

        if (payout.getStatus() == PayoutStatus.SUCCEEDED) {
            throw new ProviderEventConflictException("Payout " + payoutId
                    + " is in terminal status SUCCEEDED and cannot be transitioned to FAILED");
        }

        // Lock and manage the linked BalanceHold
        BalanceHold hold = balanceHoldRepository.findByIdForUpdate(payout.getBalanceHoldId())
                .orElseThrow(() -> new IllegalStateException("Linked BalanceHold not found: " + payout.getBalanceHoldId()));

        if (payout.getStatus() == PayoutStatus.CREATED) {
            if (hold.getStatus() == HoldStatus.ACTIVE) {
                hold.release(now);
                balanceHoldRepository.saveAndFlush(hold);
            } else if (hold.getStatus() != HoldStatus.EXPIRED) {
                throw new IllegalStateException("Cannot fail Payout " + payoutId + " from CREATED because linked hold is " + hold.getStatus());
            }
        } else {
            // PROCESSING, UNKNOWN, RECONCILIATION_REQUIRED
            if (hold.getStatus() == HoldStatus.ACTIVE) {
                hold.release(now);
                balanceHoldRepository.saveAndFlush(hold);
            } else {
                throw new IllegalStateException("Cannot fail Payout " + payoutId + " from " + payout.getStatus()
                        + " because linked hold " + hold.getId() + " is in status " + hold.getStatus() + " (must be ACTIVE)");
            }
        }

        // Mark Payout as FAILED
        payout.markFailed(now, providerOperationId);
        payoutRepository.saveAndFlush(payout);

        log.info("Marked Payout as FAILED: payoutId={}, holdId={}, providerOperationId={}",
                payout.getId(), hold.getId(), providerOperationId);

        return toResult(payout, false);
    }

    private PayoutResult toResult(Payout payout, boolean replayed) {
        return new PayoutResult(
                payout.getId(),
                payout.getStatus(),
                String.valueOf(payout.getAmountMinor()),
                payout.getCurrency(),
                payout.getBalanceHoldId(),
                payout.getProviderOperationId(),
                payout.getJournalTransactionId(),
                payout.getCreatedAt(),
                payout.getCompletedAt(),
                replayed
        );
    }
}
