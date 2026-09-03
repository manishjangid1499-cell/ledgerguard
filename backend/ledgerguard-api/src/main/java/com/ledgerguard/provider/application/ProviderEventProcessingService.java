package com.ledgerguard.provider.application;

import com.ledgerguard.funding.application.FundingFailureService;
import com.ledgerguard.funding.application.FundingSettlementService;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.payout.application.PayoutFailureService;
import com.ledgerguard.payout.application.PayoutSettlementService;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.domain.ProviderEvent;
import com.ledgerguard.provider.domain.ProviderProcessingStatus;
import com.ledgerguard.provider.infrastructure.ProviderEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProviderEventProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventProcessingService.class);

    private final ProviderEventRepository providerEventRepository;
    private final FundingOperationRepository fundingOperationRepository;
    private final FundingSettlementService fundingSettlementService;
    private final FundingFailureService fundingFailureService;
    private final PayoutRepository payoutRepository;
    private final PayoutSettlementService payoutSettlementService;
    private final PayoutFailureService payoutFailureService;
    private final ProviderConflictTransitionService providerConflictTransitionService;

    public ProviderEventProcessingService(
            ProviderEventRepository providerEventRepository,
            FundingOperationRepository fundingOperationRepository,
            FundingSettlementService fundingSettlementService,
            FundingFailureService fundingFailureService,
            PayoutRepository payoutRepository,
            PayoutSettlementService payoutSettlementService,
            PayoutFailureService payoutFailureService,
            ProviderConflictTransitionService providerConflictTransitionService
    ) {
        this.providerEventRepository = providerEventRepository;
        this.fundingOperationRepository = fundingOperationRepository;
        this.fundingSettlementService = fundingSettlementService;
        this.fundingFailureService = fundingFailureService;
        this.payoutRepository = payoutRepository;
        this.payoutSettlementService = payoutSettlementService;
        this.payoutFailureService = payoutFailureService;
        this.providerConflictTransitionService = providerConflictTransitionService;
    }

    public record ProcessingOutcome(
            int appliedCount,
            int ignoredCount,
            boolean hasPendingGap,
            boolean localOperationMissing
    ) {}

    @Transactional(propagation = Propagation.REQUIRED)
    public ProcessingOutcome processPendingEvents(UUID providerOperationId) {
        List<ProviderEvent> events = providerEventRepository.findAllByProviderOperationIdForUpdate(providerOperationId);

        long expectedSequence = 1;
        String lastKnownProviderStatus = null;
        int appliedCount = 0;
        int ignoredCount = 0;
        boolean hasPendingGap = false;
        boolean localOperationMissing = false;

        for (ProviderEvent event : events) {
            long seq = event.getEventSequence();

            if (seq < expectedSequence) {
                if (event.getProcessingStatus() == ProviderProcessingStatus.APPLIED) {
                    lastKnownProviderStatus = event.getProviderStatus();
                }
                continue;
            }

            if (seq > expectedSequence) {
                // Gap in sequence detected (e.g., sequence 2 arrived before sequence 1)
                log.info("Sequence gap for providerOperationId {}: expected={}, current={}. Halting contiguous processing.",
                        providerOperationId, expectedSequence, seq);
                hasPendingGap = true;
                break;
            }

            // seq == expectedSequence
            if (event.getProcessingStatus() == ProviderProcessingStatus.APPLIED) {
                lastKnownProviderStatus = event.getProviderStatus();
                expectedSequence++;
                continue;
            }

            if (event.getProcessingStatus() == ProviderProcessingStatus.IGNORED) {
                // Consumed sequence position, but IGNORED does not advance lastKnownProviderStatus
                expectedSequence++;
                continue;
            }

            // event is PENDING
            boolean missing = validateLocalBusinessIdentity(event);
            if (missing) {
                log.warn("Local business operation missing for event {}. Halting processing.", event.getEventId());
                localOperationMissing = true;
                break;
            }

            if (isStatusRegression(lastKnownProviderStatus, event.getProviderStatus())) {
                log.warn("Illegal status regression detected for event {}: lastKnown={}, incoming={}. Marking IGNORED.",
                        event.getEventId(), lastKnownProviderStatus, event.getProviderStatus());
                event.markIgnored(Instant.now());
                providerEventRepository.saveAndFlush(event);
                ignoredCount++;
                expectedSequence++;
            } else {
                applyBusinessSideEffects(event);
                event.markApplied(Instant.now());
                providerEventRepository.saveAndFlush(event);
                appliedCount++;
                lastKnownProviderStatus = event.getProviderStatus();
                expectedSequence++;
            }
        }

        return new ProcessingOutcome(appliedCount, ignoredCount, hasPendingGap, localOperationMissing);
    }

    private boolean validateLocalBusinessIdentity(ProviderEvent event) {
        if ("CREDIT".equalsIgnoreCase(event.getOperationType())) {
            Optional<FundingOperation> fundingOpt = fundingOperationRepository.findById(event.getClientOperationId());
            if (fundingOpt.isEmpty()) {
                return true; // Local operation missing
            }
            FundingOperation funding = fundingOpt.get();
            if (funding.getAmountMinor() != event.getAmountMinor()) {
                providerConflictTransitionService.transitionFundingToReconciliationRequired(funding.getId());
                throw new ProviderEventConflictException("Funding amount mismatch: local=" + funding.getAmountMinor() + ", event=" + event.getAmountMinor());
            }
            if (!funding.getCurrency().equalsIgnoreCase(event.getCurrency())) {
                providerConflictTransitionService.transitionFundingToReconciliationRequired(funding.getId());
                throw new ProviderEventConflictException("Funding currency mismatch: local=" + funding.getCurrency() + ", event=" + event.getCurrency());
            }
            if (funding.getProviderOperationId() != null && !funding.getProviderOperationId().equals(event.getProviderOperationId())) {
                providerConflictTransitionService.transitionFundingToReconciliationRequired(funding.getId());
                throw new ProviderEventConflictException("Funding providerOperationId mismatch: local=" + funding.getProviderOperationId() + ", event=" + event.getProviderOperationId());
            }
            return false;
        } else if ("DEBIT".equalsIgnoreCase(event.getOperationType())) {
            Optional<Payout> payoutOpt = payoutRepository.findById(event.getClientOperationId());
            if (payoutOpt.isEmpty()) {
                return true; // Local operation missing
            }
            Payout payout = payoutOpt.get();
            if (payout.getAmountMinor() != event.getAmountMinor()) {
                providerConflictTransitionService.transitionPayoutToReconciliationRequired(payout.getId());
                throw new ProviderEventConflictException("Payout amount mismatch: local=" + payout.getAmountMinor() + ", event=" + event.getAmountMinor());
            }
            if (!payout.getCurrency().equalsIgnoreCase(event.getCurrency())) {
                providerConflictTransitionService.transitionPayoutToReconciliationRequired(payout.getId());
                throw new ProviderEventConflictException("Payout currency mismatch: local=" + payout.getCurrency() + ", event=" + event.getCurrency());
            }
            if (payout.getProviderOperationId() != null && !payout.getProviderOperationId().equals(event.getProviderOperationId())) {
                providerConflictTransitionService.transitionPayoutToReconciliationRequired(payout.getId());
                throw new ProviderEventConflictException("Payout providerOperationId mismatch: local=" + payout.getProviderOperationId() + ", event=" + event.getProviderOperationId());
            }
            return false;
        } else {
            throw new ProviderEventConflictException("Unknown operationType: " + event.getOperationType());
        }
    }

    private boolean isStatusRegression(String previousStatus, String incomingStatus) {
        if (previousStatus == null) {
            return false; // Initial event (PROCESSING, SUCCEEDED, or FAILED is legal)
        }
        if ("PROCESSING".equalsIgnoreCase(previousStatus)) {
            return false; // PROCESSING -> PROCESSING, SUCCEEDED, or FAILED is legal
        }
        if ("SUCCEEDED".equalsIgnoreCase(previousStatus)) {
            // SUCCEEDED -> SUCCEEDED is legal (same-terminal no-op)
            // SUCCEEDED -> PROCESSING or FAILED is illegal regression
            return !"SUCCEEDED".equalsIgnoreCase(incomingStatus);
        }
        if ("FAILED".equalsIgnoreCase(previousStatus)) {
            // FAILED -> FAILED is legal (same-terminal no-op)
            // FAILED -> PROCESSING or SUCCEEDED is illegal regression
            return !"FAILED".equalsIgnoreCase(incomingStatus);
        }
        return false;
    }

    private void applyBusinessSideEffects(ProviderEvent event) {
        String status = event.getProviderStatus();

        if ("PROCESSING".equalsIgnoreCase(status)) {
            // Observation only: no money mutation
            return;
        }

        if ("SUCCEEDED".equalsIgnoreCase(status)) {
            if ("CREDIT".equalsIgnoreCase(event.getOperationType())) {
                PspOperationResponse pspResp = new PspOperationResponse(
                        event.getProviderOperationId(),
                        event.getClientOperationId(),
                        "CREDIT",
                        String.valueOf(event.getAmountMinor()),
                        event.getCurrency(),
                        "SUCCEEDED",
                        event.getOccurredAt().toString(),
                        event.getOccurredAt().toString(),
                        false
                );
                fundingSettlementService.settleFunding(event.getClientOperationId(), pspResp);
            } else if ("DEBIT".equalsIgnoreCase(event.getOperationType())) {
                PspOperationResponse pspResp = new PspOperationResponse(
                        event.getProviderOperationId(),
                        event.getClientOperationId(),
                        "DEBIT",
                        String.valueOf(event.getAmountMinor()),
                        event.getCurrency(),
                        "SUCCEEDED",
                        event.getOccurredAt().toString(),
                        event.getOccurredAt().toString(),
                        false
                );
                payoutSettlementService.settlePayout(event.getClientOperationId(), pspResp);
            }
            return;
        }

        if ("FAILED".equalsIgnoreCase(status)) {
            if ("DEBIT".equalsIgnoreCase(event.getOperationType())) {
                Payout payout = payoutRepository.findById(event.getClientOperationId()).orElseThrow();
                if (payout.getStatus() == PayoutStatus.PROCESSING
                        || payout.getStatus() == PayoutStatus.UNKNOWN
                        || payout.getStatus() == PayoutStatus.RECONCILIATION_REQUIRED) {
                    payoutFailureService.failPayout(payout.getId(), event.getProviderOperationId(), event.getOccurredAt());
                } else if (payout.getStatus() == PayoutStatus.SUCCEEDED) {
                    throw new ProviderEventConflictException("Payout " + payout.getId() + " is already SUCCEEDED and cannot be failed by incoming event");
                } else if (payout.getProviderOperationId() != null && !payout.getProviderOperationId().equals(event.getProviderOperationId())) {
                    throw new ProviderEventConflictException("Conflicting providerOperationId for terminal payout: expected="
                            + payout.getProviderOperationId() + ", incoming=" + event.getProviderOperationId());
                }
            } else if ("CREDIT".equalsIgnoreCase(event.getOperationType())) {
                FundingOperation funding = fundingOperationRepository.findById(event.getClientOperationId()).orElseThrow();
                if (funding.getStatus() == FundingStatus.PROCESSING
                        || funding.getStatus() == FundingStatus.UNKNOWN
                        || funding.getStatus() == FundingStatus.RECONCILIATION_REQUIRED) {
                    fundingFailureService.failFunding(funding.getId(), event.getProviderOperationId(), event.getOccurredAt());
                } else if (funding.getStatus() == FundingStatus.SUCCEEDED) {
                    throw new ProviderEventConflictException("Funding " + funding.getId() + " is already SUCCEEDED and cannot be failed by incoming event");
                } else if (funding.getProviderOperationId() != null && !funding.getProviderOperationId().equals(event.getProviderOperationId())) {
                    throw new ProviderEventConflictException("Conflicting providerOperationId for terminal funding: expected="
                            + funding.getProviderOperationId() + ", incoming=" + event.getProviderOperationId());
                }
            }
        }
    }
}
