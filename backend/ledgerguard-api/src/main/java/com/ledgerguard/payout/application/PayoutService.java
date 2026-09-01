package com.ledgerguard.payout.application;

import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.funding.infrastructure.PspProtocolException;
import com.ledgerguard.funding.infrastructure.PspTransportException;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Non-transactional orchestrator coordinating the 3-phase external payout workflow:
 * 1. PayoutCreationService (transactional): Persist idempotency, create BalanceHold, commit PROCESSING Payout.
 * 2. PspClient (non-transactional): Outbound HTTP DEBIT call without holding DB connections or locks.
 * 3. Settlement / Failure (transactional):
 *    - On authoritative success: PayoutSettlementService consumes hold, locks snapshots, posts double-entry journal.
 *    - On definite failure (TEMPORARY_500 contract): PayoutFailureService releases hold, marks Payout FAILED.
 *    - On ambiguous timeout/transport failure: Payout remains PROCESSING and BalanceHold remains ACTIVE.
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final PayoutCreationService payoutCreationService;
    private final PayoutSettlementService payoutSettlementService;
    private final PayoutFailureService payoutFailureService;
    private final PspClient pspClient;

    public PayoutService(
            PayoutCreationService payoutCreationService,
            PayoutSettlementService payoutSettlementService,
            PayoutFailureService payoutFailureService,
            PspClient pspClient
    ) {
        this.payoutCreationService = payoutCreationService;
        this.payoutSettlementService = payoutSettlementService;
        this.payoutFailureService = payoutFailureService;
        this.pspClient = pspClient;
    }

    public PayoutResult requestPayout(CreatePayoutCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Step 1: Create durable Payout in PROCESSING status with ACTIVE BalanceHold (or recover existing on replay)
        PayoutCreationService.PayoutCreationResult creationResult = payoutCreationService.createOrGetProcessingPayout(command);
        Payout payout = creationResult.payout();

        if (creationResult.replayed()) {
            log.info("Returning existing Payout on idempotency replay without new PSP attempt: id={}, status={}",
                    payout.getId(), payout.getStatus());
            return toResult(payout, true);
        }

        // Step 2: Make external HTTP DEBIT call outside any DB transaction
        PspOperationResponse pspResponse;
        try {
            pspResponse = pspClient.createOperation(
                    payout.getId(),
                    "DEBIT",
                    String.valueOf(payout.getAmountMinor()),
                    "INR"
            );
        } catch (PspProtocolException ex) {
            if (ex.getStatusCode() != null && ex.getStatusCode() == 500) {
                // Definite provider failure under simulator TEMPORARY_500 contract
                log.warn("Definite PSP failure (500) for payout {}. Releasing hold and marking FAILED.", payout.getId());
                return payoutFailureService.failPayout(payout.getId());
            }
            log.warn("PSP protocol error for payout {}: status={}, message={}. Preserving PROCESSING state.",
                    payout.getId(), ex.getStatusCode(), ex.getMessage());
            return toResult(payout, false);
        } catch (PspTransportException ex) {
            log.warn("PSP transport timeout/failure for payout {}. Preserving PROCESSING state and ACTIVE hold. Error: {}",
                    payout.getId(), ex.getMessage());
            return toResult(payout, false);
        } catch (Exception ex) {
            log.warn("Unexpected error contacting PSP for payout {}. Preserving PROCESSING state and ACTIVE hold. Error: {}",
                    payout.getId(), ex.getMessage());
            return toResult(payout, false);
        }

        // Step 3: Validate and commit settlement if provider confirmed SUCCEEDED
        if (pspResponse != null && "SUCCEEDED".equalsIgnoreCase(pspResponse.status())) {
            try {
                return payoutSettlementService.settlePayout(payout.getId(), pspResponse);
            } catch (Exception ex) {
                log.error("Local settlement failure after PSP success for payout {}. Preserving PROCESSING state. Error: {}",
                        payout.getId(), ex.getMessage(), ex);
                return toResult(payout, false);
            }
        }

        log.warn("Unconfirmed PSP response for payout {}. Preserving PROCESSING state and ACTIVE hold.", payout.getId());
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
