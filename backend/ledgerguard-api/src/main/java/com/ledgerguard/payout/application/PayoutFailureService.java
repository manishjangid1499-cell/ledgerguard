package com.ledgerguard.payout.application;

import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.domain.PayoutValidationException;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
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
 * upon a definitive provider failure (such as TEMPORARY_500 in the simulator contract).
 */
@Service
public class PayoutFailureService {

    private static final Logger log = LoggerFactory.getLogger(PayoutFailureService.class);

    private final PayoutRepository payoutRepository;
    private final BalanceHoldRepository balanceHoldRepository;

    public PayoutFailureService(
            PayoutRepository payoutRepository,
            BalanceHoldRepository balanceHoldRepository
    ) {
        this.payoutRepository = payoutRepository;
        this.balanceHoldRepository = balanceHoldRepository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public PayoutResult failPayout(UUID payoutId) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");

        Payout payout = payoutRepository.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new PayoutValidationException("Payout not found: " + payoutId));

        if (payout.getStatus() == PayoutStatus.FAILED || payout.getStatus() == PayoutStatus.SUCCEEDED) {
            log.info("Payout {} already terminal in status {}, returning existing result", payoutId, payout.getStatus());
            return toResult(payout, false);
        }

        if (payout.getStatus() != PayoutStatus.PROCESSING) {
            throw new IllegalStateException("Cannot fail Payout " + payoutId + " in status " + payout.getStatus());
        }

        // Lock and release the linked BalanceHold
        BalanceHold hold = balanceHoldRepository.findByIdForUpdate(payout.getBalanceHoldId())
                .orElseThrow(() -> new IllegalStateException("Linked BalanceHold not found: " + payout.getBalanceHoldId()));

        Instant now = Instant.now();

        if (hold.getStatus() == HoldStatus.ACTIVE) {
            hold.release(now);
        } else {
            throw new IllegalStateException("Cannot fail Payout " + payoutId + " because linked hold " + hold.getId() + " is in status " + hold.getStatus());
        }

        balanceHoldRepository.saveAndFlush(hold);

        // Mark Payout as FAILED
        payout.markFailed(now);
        payoutRepository.saveAndFlush(payout);

        log.info("Marked Payout as FAILED and released hold: payoutId={}, holdId={}", payout.getId(), hold.getId());

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
