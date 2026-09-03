package com.ledgerguard.funding.application;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.domain.FundingValidationException;
import com.ledgerguard.funding.domain.PspClearingAccountException;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.funding.infrastructure.PspProtocolException;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.application.PostingResult;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional boundary for authoritative local financial settlement of verified external funding.
 * <p>
 * Performs row-level pessimistic locking on FundingOperation and deterministic snapshot locking on
 * customer and PSP_CLEARING accounts, posts the double-entry journal, and transitions the funding
 * operation to SUCCEEDED.
 */
@Service
public class FundingSettlementService {

    private static final Logger log = LoggerFactory.getLogger(FundingSettlementService.class);

    private final FundingOperationRepository fundingOperationRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;
    private final LedgerPostingService ledgerPostingService;
    private final jakarta.persistence.EntityManager entityManager;

    public FundingSettlementService(
            FundingOperationRepository fundingOperationRepository,
            LedgerAccountRepository ledgerAccountRepository,
            LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository,
            LedgerPostingService ledgerPostingService,
            jakarta.persistence.EntityManager entityManager
    ) {
        this.fundingOperationRepository = fundingOperationRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerBalanceSnapshotRepository = ledgerBalanceSnapshotRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.entityManager = entityManager;
    }

    /**
     * Executes atomic local ledger settlement for a verified PSP funding operation.
     *
     * @param fundingId durable funding operation ID
     * @param pspResponse validated external PSP response
     * @return updated FundingOperation in SUCCEEDED state
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public FundingOperation settleFunding(UUID fundingId, PspOperationResponse pspResponse) {
        Objects.requireNonNull(fundingId, "Funding ID must not be null");
        Objects.requireNonNull(pspResponse, "PSP operation response must not be null");

        // 1. Lock FundingOperation row with pessimistic write lock and refresh entity state
        FundingOperation funding = fundingOperationRepository.findByIdForUpdate(fundingId)
                .orElseThrow(() -> new IllegalStateException("FundingOperation not found for settlement: " + fundingId));
        entityManager.refresh(funding);

        // 2. If already SUCCEEDED, another concurrent settlement attempt or replay already settled
        if (funding.getStatus() == FundingStatus.SUCCEEDED) {
            if (!Objects.equals(funding.getProviderOperationId(), pspResponse.providerOperationId())) {
                throw new com.ledgerguard.provider.application.ProviderEventConflictException(
                        "Conflicting providerOperationId for already SUCCEEDED funding: expected="
                                + funding.getProviderOperationId() + ", incoming=" + pspResponse.providerOperationId());
            }
            validateProviderResponse(funding, pspResponse);
            log.info("FundingOperation {} is already SUCCEEDED with matching providerOpId {}, returning existing settlement",
                    fundingId, pspResponse.providerOperationId());
            return funding;
        }

        if (funding.getStatus() == FundingStatus.FAILED) {
            throw new com.ledgerguard.provider.application.ProviderEventConflictException(
                    "FundingOperation " + fundingId + " is in terminal status FAILED and cannot be settled as SUCCEEDED");
        }

        // 3. Validate provider response against durable FundingOperation
        validateProviderResponse(funding, pspResponse);

        // 4. Revalidate customer wallet
        LedgerAccount customerAccount = ledgerAccountRepository.findById(funding.getCustomerLedgerAccountId())
                .orElseThrow(() -> new FundingValidationException("Customer ledger account not found: " + funding.getCustomerLedgerAccountId()));

        if (customerAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new FundingValidationException("Customer ledger account is not ACTIVE: " + customerAccount.getId());
        }
        if (customerAccount.getAccountType() != AccountType.CUSTOMER) {
            throw new FundingValidationException("Referenced account is not of type CUSTOMER: " + customerAccount.getId());
        }
        if (!customerAccount.getOwnerUserId().equals(funding.getInitiatedByUserId())) {
            throw new FundingValidationException("Customer ledger account owner mismatch: " + customerAccount.getId());
        }
        if (!"INR".equals(customerAccount.getCurrency())) {
            throw new FundingValidationException("Customer ledger account currency must be INR: " + customerAccount.getCurrency());
        }

        // 5. Resolve system PSP_CLEARING account
        List<LedgerAccount> clearingAccounts = ledgerAccountRepository.findAllByAccountType(AccountType.PSP_CLEARING).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE && "INR".equals(a.getCurrency()) && a.getOwnerUserId() == null)
                .toList();

        if (clearingAccounts.isEmpty()) {
            throw new PspClearingAccountException("No active INR PSP_CLEARING ledger account configured");
        }
        if (clearingAccounts.size() > 1) {
            throw new PspClearingAccountException("Multiple active INR PSP_CLEARING ledger accounts found: " + clearingAccounts.size());
        }
        LedgerAccount pspClearingAccount = clearingAccounts.get(0);

        // 6. Deterministic balance snapshot row locking (ordered ascending UUID)
        List<UUID> accountsToLock = List.of(customerAccount.getId(), pspClearingAccount.getId());
        List<LedgerBalanceSnapshot> lockedSnapshots = ledgerBalanceSnapshotRepository.findAllByLedgerAccountIdInForUpdateOrdered(accountsToLock);
        if (lockedSnapshots.size() != 2) {
            throw new IllegalStateException("Expected 2 balance snapshots locked for settlement, but locked " + lockedSnapshots.size());
        }

        // 7. Post double-entry settlement journal: DEBIT PSP_CLEARING, CREDIT CUSTOMER
        PostingLine debitClearing = PostingLine.debit(pspClearingAccount.getId(), funding.getAmountMinor());
        PostingLine creditCustomer = PostingLine.credit(customerAccount.getId(), funding.getAmountMinor());
        PostJournalCommand postCommand = new PostJournalCommand(List.of(debitClearing, creditCustomer));

        PostingResult postingResult = ledgerPostingService.post(postCommand);

        // 8. Transition FundingOperation to SUCCEEDED
        funding.markSucceeded(pspResponse.providerOperationId(), postingResult.journalTransactionId(), Instant.now());
        fundingOperationRepository.saveAndFlush(funding);

        log.info("Committed funding settlement: fundingId={}, providerOpId={}, journalTxnId={}, amount={}",
                funding.getId(), pspResponse.providerOperationId(), postingResult.journalTransactionId(), funding.getAmountMinor());

        return funding;
    }

    private void validateProviderResponse(FundingOperation funding, PspOperationResponse pspResponse) {
        if (pspResponse.providerOperationId() == null) {
            throw new PspProtocolException("PSP response missing providerOperationId");
        }
        if (!funding.getId().equals(pspResponse.clientOperationId())) {
            throw new PspProtocolException("PSP clientOperationId mismatch: expected=" + funding.getId() + ", actual=" + pspResponse.clientOperationId());
        }
        if (!"CREDIT".equalsIgnoreCase(pspResponse.operationType())) {
            throw new PspProtocolException("PSP operationType mismatch: expected=CREDIT, actual=" + pspResponse.operationType());
        }
        if (!"SUCCEEDED".equalsIgnoreCase(pspResponse.status())) {
            throw new PspProtocolException("PSP status is not SUCCEEDED: " + pspResponse.status());
        }
        if (!"INR".equalsIgnoreCase(pspResponse.currency())) {
            throw new PspProtocolException("PSP currency mismatch: expected=INR, actual=" + pspResponse.currency());
        }
        long pspAmount;
        try {
            pspAmount = Long.parseLong(pspResponse.amountMinor());
        } catch (NumberFormatException ex) {
            throw new PspProtocolException("PSP amountMinor is not a valid integer: " + pspResponse.amountMinor());
        }
        if (pspAmount != funding.getAmountMinor()) {
            throw new PspProtocolException("PSP amountMinor mismatch: expected=" + funding.getAmountMinor() + ", actual=" + pspAmount);
        }
    }
}
