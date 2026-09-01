package com.ledgerguard.funding.application;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Non-transactional application orchestrator for external wallet funding.
 * <p>
 * Orchestrates the three distinct lifecycle phases:
 * 1. Durable transactional creation / idempotency claim (FundingCreationService)
 * 2. Non-transactional external HTTP call to PSP simulator (PspClient)
 * 3. Durable transactional local ledger settlement (FundingSettlementService)
 * <p>
 * Guarantees that no database transaction or locks are held during the external PSP network interaction.
 */
@Service
public class FundingService {

    public static final String OPERATION_NAMESPACE = "external-funding:v1";

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final LedgerAccountRepository ledgerAccountRepository;
    private final FundingCreationService fundingCreationService;
    private final PspClient pspClient;
    private final FundingSettlementService fundingSettlementService;
    private final FundingOperationRepository fundingOperationRepository;

    public FundingService(
            LedgerAccountRepository ledgerAccountRepository,
            FundingCreationService fundingCreationService,
            PspClient pspClient,
            FundingSettlementService fundingSettlementService,
            FundingOperationRepository fundingOperationRepository
    ) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.fundingCreationService = fundingCreationService;
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

        // 4. Phase 1: Durable creation transaction
        FundingCreationService.CreationOutcome creationOutcome =
                fundingCreationService.createOrGetFunding(command, customerAccount, fingerprint);

        FundingOperation funding = creationOutcome.funding();

        // 5. If already SUCCEEDED (e.g. idempotency replay of a previously settled funding), return immediately
        if (funding.getStatus() == FundingStatus.SUCCEEDED) {
            log.info("FundingOperation {} is already SUCCEEDED on replay, skipping PSP call", funding.getId());
            return FundingResult.from(funding, true);
        }

        // 6. Phase 2: Outbound PSP network call (executed with NO LedgerGuard database transaction)
        PspOperationResponse pspResponse;
        try {
            pspResponse = pspClient.createOperation(
                    funding.getId(),
                    "CREDIT",
                    String.valueOf(funding.getAmountMinor()),
                    funding.getCurrency()
            );
        } catch (PspTransportException | PspProtocolException ex) {
            log.warn("PSP interaction unconfirmed for funding {}: {}", funding.getId(), ex.getMessage());
            // Provider outcome unconfirmed: preserve durable PROCESSING state, 0 ledger credit
            return FundingResult.from(funding, creationOutcome.replayed());
        } catch (Exception ex) {
            log.error("Unexpected error calling PSP for funding {}: {}", funding.getId(), ex.getMessage(), ex);
            return FundingResult.from(funding, creationOutcome.replayed());
        }

        // 7. Phase 3: Authoritative local settlement transaction
        try {
            FundingOperation settledFunding = fundingSettlementService.settleFunding(funding.getId(), pspResponse);
            return FundingResult.from(settledFunding, creationOutcome.replayed());
        } catch (Exception ex) {
            log.error("Local settlement transaction failed for funding {}: {}", funding.getId(), ex.getMessage(), ex);
            // Settlement transaction rolled back: reload fresh state (remains PROCESSING)
            FundingOperation reloaded = fundingOperationRepository.findById(funding.getId())
                    .orElse(funding);
            return FundingResult.from(reloaded, creationOutcome.replayed());
        }
    }
}
