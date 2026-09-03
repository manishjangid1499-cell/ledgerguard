package com.ledgerguard.funding.application;

import com.ledgerguard.common.application.SubmissionPreparationResult;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.domain.FundingValidationException;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.funding.infrastructure.PspProtocolException;
import com.ledgerguard.funding.infrastructure.PspTransportException;
import com.ledgerguard.idempotency.domain.RequestFingerprint;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.provider.application.ProviderConflictTransitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Non-transactional application orchestrator for external wallet funding in Phase 23.
 * <p>
 * Enforces that at most ONE provider CREATE POST attempt occurs per logical operation.
 * Guarantees that no database transactions or locks are held during external PSP network calls.
 */
@Service
public class FundingService {

    public static final String OPERATION_NAMESPACE = "external-funding:v1";
    public static final Duration INITIAL_POLL_DELAY = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final LedgerAccountRepository ledgerAccountRepository;
    private final FundingCreationService fundingCreationService;
    private final FundingSubmissionService fundingSubmissionService;
    private final FundingTransitionService fundingTransitionService;
    private final FundingFailureService fundingFailureService;
    private final ProviderConflictTransitionService providerConflictTransitionService;
    private final PspClient pspClient;
    private final FundingSettlementService fundingSettlementService;
    private final FundingOperationRepository fundingOperationRepository;

    public FundingService(
            LedgerAccountRepository ledgerAccountRepository,
            FundingCreationService fundingCreationService,
            FundingSubmissionService fundingSubmissionService,
            FundingTransitionService fundingTransitionService,
            FundingFailureService fundingFailureService,
            ProviderConflictTransitionService providerConflictTransitionService,
            PspClient pspClient,
            FundingSettlementService fundingSettlementService,
            FundingOperationRepository fundingOperationRepository
    ) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.fundingCreationService = fundingCreationService;
        this.fundingSubmissionService = fundingSubmissionService;
        this.fundingTransitionService = fundingTransitionService;
        this.fundingFailureService = fundingFailureService;
        this.providerConflictTransitionService = providerConflictTransitionService;
        this.pspClient = pspClient;
        this.fundingSettlementService = fundingSettlementService;
        this.fundingOperationRepository = fundingOperationRepository;
    }

    /**
     * Orchestrates an external wallet funding request.
     *
     * @param command validated funding command
     * @return FundingResult indicating outcome and status
     */
    public FundingResult fundWallet(CreateFundingCommand command) {
        Objects.requireNonNull(command, "CreateFundingCommand must not be null");

        long amountMinor = command.amount().getMinorUnits();

        // 1. Validate amount and currency
        if (amountMinor <= 0) {
            throw new FundingValidationException("Funding amount must be strictly positive: " + amountMinor);
        }
        if (!"INR".equals(command.amount().getCurrencyCode())) {
            throw new FundingValidationException("Funding currency must be INR: " + command.amount().getCurrencyCode());
        }

        // 2. Resolve and validate customer wallet from authenticated actor
        List<LedgerAccount> customerAccounts = ledgerAccountRepository.findByOwnerUserId(command.actorUserId());
        if (customerAccounts.isEmpty()) {
            throw new FundingValidationException("No ledger account found for customer: " + command.actorUserId());
        }
        LedgerAccount customerAccount = customerAccounts.stream()
                .filter(a -> a.getAccountType() == AccountType.CUSTOMER)
                .findFirst()
                .orElseThrow(() -> new FundingValidationException("Customer has no CUSTOMER ledger account: " + command.actorUserId()));

        if (customerAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new FundingValidationException("Customer wallet is not active: " + customerAccount.getId());
        }

        // 3. Construct deterministic request fingerprint
        String canonicalPayload = String.format(
                "%s\ncustomer=%s\namountMinor=%d\ncurrency=%s",
                OPERATION_NAMESPACE,
                customerAccount.getId(),
                amountMinor,
                command.amount().getCurrencyCode()
        );
        RequestFingerprint fingerprint = RequestFingerprint.of(canonicalPayload);

        // 4. Phase 1: Durable creation transaction (creates in CREATED status if new)
        FundingCreationService.CreationOutcome creationOutcome =
                fundingCreationService.createOrGetFunding(command, customerAccount, fingerprint);

        FundingOperation funding = creationOutcome.funding();

        // 5. If already terminal (e.g. idempotency replay of a settled or failed funding), return immediately
        if (funding.getStatus() == FundingStatus.SUCCEEDED || funding.getStatus() == FundingStatus.FAILED) {
            log.info("FundingOperation {} is already terminal ({}) on replay, skipping PSP call", funding.getId(), funding.getStatus());
            return FundingResult.from(funding, creationOutcome.replayed());
        }

        // 6. Phase 2: Atomic submission claim (transitions CREATED -> PROCESSING under row lock)
        SubmissionPreparationResult<FundingOperation> claimResult =
                fundingSubmissionService.claimSubmission(funding.getId(), Instant.now().plus(INITIAL_POLL_DELAY));

        if (!claimResult.submissionClaimed()) {
            log.info("FundingOperation {} was not claimed for submission (status={}), skipping provider POST",
                    funding.getId(), claimResult.operation().getStatus());
            return FundingResult.from(claimResult.operation(), creationOutcome.replayed());
        }

        funding = claimResult.operation();

        // 7. Phase 3: Outbound PSP network call (at most ONE provider POST attempt, executed with NO DB transaction)
        PspOperationResponse pspResponse;
        try {
            pspResponse = pspClient.createOperation(
                    funding.getId(),
                    "CREDIT",
                    String.valueOf(funding.getAmountMinor()),
                    funding.getCurrency()
            );
        } catch (PspTransportException ex) {
            log.warn("PSP transport error for funding {}: marking UNKNOWN", funding.getId());
            FundingOperation updated = fundingTransitionService.markUnknown(
                    funding.getId(), Instant.now(), Instant.now().plus(INITIAL_POLL_DELAY));
            return FundingResult.from(updated, creationOutcome.replayed());
        } catch (PspProtocolException ex) {
            log.warn("PSP protocol error for funding {}: status={}, type={}",
                    funding.getId(), ex.getStatusCode(), ex.getProviderErrorType());

            if (ex.getStatusCode() != null && ex.getStatusCode() == 500
                    && "urn:ledgerguard:psp:error:temporary-failure".equals(ex.getProviderErrorType())) {
                FundingOperation failed = fundingFailureService.failFunding(funding.getId(), null, Instant.now());
                return FundingResult.from(failed, creationOutcome.replayed());
            }

            if (ex.getStatusCode() != null && ex.getStatusCode() == 409
                    && "urn:ledgerguard:psp:error:conflicting-replay".equals(ex.getProviderErrorType())) {
                providerConflictTransitionService.transitionFundingToReconciliationRequired(funding.getId());
                FundingOperation reloaded = fundingOperationRepository.findById(funding.getId()).orElse(funding);
                return FundingResult.from(reloaded, creationOutcome.replayed());
            }

            if (ex.getStatusCode() != null && (ex.getStatusCode() == 400 || ex.getStatusCode() == 422)) {
                FundingOperation failed = fundingFailureService.failFunding(funding.getId(), null, Instant.now());
                return FundingResult.from(failed, creationOutcome.replayed());
            }

            // Ambiguous (generic 500, malformed, 408, 429, missing body, etc.)
            FundingOperation updated = fundingTransitionService.markUnknown(
                    funding.getId(), Instant.now(), Instant.now().plus(INITIAL_POLL_DELAY));
            return FundingResult.from(updated, creationOutcome.replayed());
        } catch (Exception ex) {
            log.error("Unexpected error calling PSP for funding {}: marking UNKNOWN", funding.getId(), ex);
            FundingOperation updated = fundingTransitionService.markUnknown(
                    funding.getId(), Instant.now(), Instant.now().plus(INITIAL_POLL_DELAY));
            return FundingResult.from(updated, creationOutcome.replayed());
        }

        // 8. Phase 4: Handle provider response
        if ("SUCCEEDED".equalsIgnoreCase(pspResponse.status())) {
            try {
                FundingOperation settled = fundingSettlementService.settleFunding(funding.getId(), pspResponse);
                return FundingResult.from(settled, creationOutcome.replayed());
            } catch (Exception ex) {
                log.error("Settlement failed for funding {}: remaining in nonterminal state", funding.getId(), ex);
                FundingOperation reloaded = fundingOperationRepository.findById(funding.getId()).orElse(funding);
                return FundingResult.from(reloaded, creationOutcome.replayed());
            }
        } else if ("FAILED".equalsIgnoreCase(pspResponse.status())) {
            FundingOperation failed = fundingFailureService.failFunding(funding.getId(), pspResponse.providerOperationId(), Instant.now());
            return FundingResult.from(failed, creationOutcome.replayed());
        } else {
            // PROCESSING
            FundingOperation reloaded = fundingOperationRepository.findById(funding.getId()).orElse(funding);
            return FundingResult.from(reloaded, creationOutcome.replayed());
        }
    }
}
