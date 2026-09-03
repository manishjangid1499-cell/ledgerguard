package com.ledgerguard.payout.application;

import com.ledgerguard.common.application.SubmissionPreparationResult;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.funding.infrastructure.PspProtocolException;
import com.ledgerguard.funding.infrastructure.PspTransportException;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.application.ProviderConflictTransitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Non-transactional orchestrator coordinating the external payout workflow in Phase 23:
 * 1. PayoutCreationService (transactional): Persist idempotency, create BalanceHold, commit CREATED Payout.
 * 2. PayoutSubmissionService (transactional): Atomic claim transitioning CREATED -> PROCESSING under row lock.
 * 3. PspClient (non-transactional): Outbound HTTP DEBIT call without holding DB connections or locks (at most ONE attempt).
 * 4. Settlement / Failure / Transition (transactional):
 *    - On authoritative success: PayoutSettlementService consumes hold, locks snapshots, posts double-entry journal.
 *    - On definite failure: PayoutFailureService releases hold, marks Payout FAILED.
 *    - On ambiguous timeout/transport failure: Payout transitions to UNKNOWN; BalanceHold remains ACTIVE.
 *    - On conflict: ProviderConflictTransitionService transitions to RECONCILIATION_REQUIRED; BalanceHold remains ACTIVE.
 */
@Service
public class PayoutService {

    public static final Duration INITIAL_POLL_DELAY = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final PayoutCreationService payoutCreationService;
    private final PayoutSubmissionService payoutSubmissionService;
    private final PayoutTransitionService payoutTransitionService;
    private final PayoutSettlementService payoutSettlementService;
    private final PayoutFailureService payoutFailureService;
    private final ProviderConflictTransitionService providerConflictTransitionService;
    private final PayoutRepository payoutRepository;
    private final PspClient pspClient;

    public PayoutService(
            PayoutCreationService payoutCreationService,
            PayoutSubmissionService payoutSubmissionService,
            PayoutTransitionService payoutTransitionService,
            PayoutSettlementService payoutSettlementService,
            PayoutFailureService payoutFailureService,
            ProviderConflictTransitionService providerConflictTransitionService,
            PayoutRepository payoutRepository,
            PspClient pspClient
    ) {
        this.payoutCreationService = payoutCreationService;
        this.payoutSubmissionService = payoutSubmissionService;
        this.payoutTransitionService = payoutTransitionService;
        this.payoutSettlementService = payoutSettlementService;
        this.payoutFailureService = payoutFailureService;
        this.providerConflictTransitionService = providerConflictTransitionService;
        this.payoutRepository = payoutRepository;
        this.pspClient = pspClient;
    }

    public PayoutResult requestPayout(CreatePayoutCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Step 1: Create durable Payout (CREATED status) with ACTIVE BalanceHold (or recover existing on replay)
        PayoutCreationService.PayoutCreationResult creationResult = payoutCreationService.createOrGetProcessingPayout(command);
        Payout payout = creationResult.payout();

        // If already terminal on replay, return immediately
        if (payout.getStatus() == PayoutStatus.SUCCEEDED || payout.getStatus() == PayoutStatus.FAILED) {
            log.info("Returning existing terminal Payout on idempotency replay without new PSP attempt: id={}, status={}",
                    payout.getId(), payout.getStatus());
            return toResult(payout, true);
        }

        // Step 2: Atomic submission claim (transitions CREATED -> PROCESSING under row lock)
        SubmissionPreparationResult<Payout> claimResult =
                payoutSubmissionService.claimSubmission(payout.getId(), Instant.now().plus(INITIAL_POLL_DELAY));

        if (!claimResult.submissionClaimed()) {
            log.info("Payout {} was not claimed for submission (status={}), skipping provider POST",
                    payout.getId(), claimResult.operation().getStatus());
            return toResult(claimResult.operation(), creationResult.replayed());
        }

        payout = claimResult.operation();

        // Step 3: Make external HTTP DEBIT call outside any DB transaction (at most ONE attempt)
        PspOperationResponse pspResponse;
        try {
            pspResponse = pspClient.createOperation(
                    payout.getId(),
                    "DEBIT",
                    String.valueOf(payout.getAmountMinor()),
                    "INR"
            );
        } catch (PspTransportException ex) {
            log.warn("PSP transport timeout/failure for payout {}. Marking UNKNOWN and preserving ACTIVE hold. Error: {}",
                    payout.getId(), ex.getMessage());
            Payout updated = payoutTransitionService.markUnknown(
                    payout.getId(), Instant.now(), Instant.now().plus(INITIAL_POLL_DELAY));
            return toResult(updated, creationResult.replayed());
        } catch (PspProtocolException ex) {
            log.warn("PSP protocol error for payout {}: status={}, type={}, message={}",
                    payout.getId(), ex.getStatusCode(), ex.getProviderErrorType(), ex.getMessage());

            if (ex.getStatusCode() != null && ex.getStatusCode() == 500
                    && "urn:ledgerguard:psp:error:temporary-failure".equals(ex.getProviderErrorType())) {
                log.warn("Definite PSP failure (500 temporary-failure) for payout {}. Releasing hold and marking FAILED.", payout.getId());
                return payoutFailureService.failPayout(payout.getId(), null, Instant.now());
            }

            if (ex.getStatusCode() != null && ex.getStatusCode() == 409
                    && "urn:ledgerguard:psp:error:conflicting-replay".equals(ex.getProviderErrorType())) {
                providerConflictTransitionService.transitionPayoutToReconciliationRequired(payout.getId());
                Payout reloaded = payoutRepository.findById(payout.getId()).orElse(payout);
                return toResult(reloaded, creationResult.replayed());
            }

            if (ex.getStatusCode() != null && (ex.getStatusCode() == 400 || ex.getStatusCode() == 422)) {
                log.warn("Definite PSP client rejection ({}) for payout {}. Releasing hold and marking FAILED.",
                        ex.getStatusCode(), payout.getId());
                return payoutFailureService.failPayout(payout.getId(), null, Instant.now());
            }

            // Ambiguous (generic 500, 408, 429, malformed, missing body)
            Payout updated = payoutTransitionService.markUnknown(
                    payout.getId(), Instant.now(), Instant.now().plus(INITIAL_POLL_DELAY));
            return toResult(updated, creationResult.replayed());
        } catch (Exception ex) {
            log.error("Unexpected error contacting PSP for payout {}. Marking UNKNOWN and preserving ACTIVE hold. Error: {}",
                    payout.getId(), ex.getMessage(), ex);
            Payout updated = payoutTransitionService.markUnknown(
                    payout.getId(), Instant.now(), Instant.now().plus(INITIAL_POLL_DELAY));
            return toResult(updated, creationResult.replayed());
        }

        // Step 4: Handle validated provider response
        if (pspResponse != null && "SUCCEEDED".equalsIgnoreCase(pspResponse.status())) {
            try {
                return payoutSettlementService.settlePayout(payout.getId(), pspResponse);
            } catch (Exception ex) {
                log.error("Local settlement failure after PSP success for payout {}. Marking UNKNOWN. Error: {}",
                        payout.getId(), ex.getMessage(), ex);
                Payout updated = payoutTransitionService.markUnknown(
                        payout.getId(), Instant.now(), Instant.now().plus(INITIAL_POLL_DELAY));
                return toResult(updated, creationResult.replayed());
            }
        } else if (pspResponse != null && "FAILED".equalsIgnoreCase(pspResponse.status())) {
            return payoutFailureService.failPayout(payout.getId(), pspResponse.providerOperationId(), Instant.now());
        }

        Payout reloaded = payoutRepository.findById(payout.getId()).orElse(payout);
        return toResult(reloaded, creationResult.replayed());
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
