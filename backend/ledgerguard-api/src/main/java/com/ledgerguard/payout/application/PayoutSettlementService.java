package com.ledgerguard.payout.application;

import com.ledgerguard.funding.domain.PspClearingAccountException;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional service to settle an external Payout upon receiving authoritative PSP confirmation.
 * <p>
 * Locks the Payout row, consumes the linked BalanceHold, deterministically locks snapshots,
 * and posts a balanced double-entry journal (DEBIT source wallet, CREDIT PSP_CLEARING).
 */
@Service
public class PayoutSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PayoutSettlementService.class);

    private final PayoutRepository payoutRepository;
    private final BalanceHoldRepository balanceHoldRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;
    private final LedgerPostingService ledgerPostingService;
    private final jakarta.persistence.EntityManager entityManager;

    public PayoutSettlementService(
            PayoutRepository payoutRepository,
            BalanceHoldRepository balanceHoldRepository,
            LedgerAccountRepository ledgerAccountRepository,
            LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository,
            LedgerPostingService ledgerPostingService,
            jakarta.persistence.EntityManager entityManager
    ) {
        this.payoutRepository = payoutRepository;
        this.balanceHoldRepository = balanceHoldRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerBalanceSnapshotRepository = ledgerBalanceSnapshotRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public PayoutResult settlePayout(UUID payoutId, PspOperationResponse response) {
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        Objects.requireNonNull(response, "response must not be null");

        Payout payout = payoutRepository.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new PayoutValidationException("Payout not found: " + payoutId));
        entityManager.refresh(payout);

        if (payout.getStatus() == PayoutStatus.SUCCEEDED) {
            if (!Objects.equals(payout.getProviderOperationId(), response.providerOperationId())) {
                throw new com.ledgerguard.provider.application.ProviderEventConflictException(
                        "Conflicting providerOperationId for already SUCCEEDED payout: expected="
                                + payout.getProviderOperationId() + ", incoming=" + response.providerOperationId());
            }
            validatePspResponse(payout, response);
            log.info("Payout {} already SUCCEEDED with matching providerOpId {}, returning existing result", payoutId, response.providerOperationId());
            return toResult(payout, false);
        }

        if (payout.getStatus() == PayoutStatus.FAILED) {
            throw new com.ledgerguard.provider.application.ProviderEventConflictException(
                    "Payout " + payoutId + " is in terminal status FAILED and cannot be settled as SUCCEEDED");
        }

        if (payout.getStatus() != PayoutStatus.PROCESSING
                && payout.getStatus() != PayoutStatus.UNKNOWN
                && payout.getStatus() != PayoutStatus.RECONCILIATION_REQUIRED) {
            throw new IllegalStateException("Cannot settle Payout " + payoutId + " in status " + payout.getStatus());
        }

        // Validate PSP response exact identity & fields
        validatePspResponse(payout, response);

        // Lock and validate linked BalanceHold
        BalanceHold hold = balanceHoldRepository.findByIdForUpdate(payout.getBalanceHoldId())
                .orElseThrow(() -> new IllegalStateException("Linked BalanceHold not found: " + payout.getBalanceHoldId()));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new IllegalStateException("Linked BalanceHold " + hold.getId() + " is not ACTIVE, but " + hold.getStatus());
        }
        if (!hold.getLedgerAccountId().equals(payout.getSourceLedgerAccountId())) {
            throw new IllegalStateException("Hold account " + hold.getLedgerAccountId() + " does not match payout source " + payout.getSourceLedgerAccountId());
        }
        if (hold.getAmountMinor() != payout.getAmountMinor()) {
            throw new IllegalStateException("Hold amount " + hold.getAmountMinor() + " does not match payout amount " + payout.getAmountMinor());
        }
        if (!"INR".equals(hold.getCurrency())) {
            throw new IllegalStateException("Hold currency " + hold.getCurrency() + " must be INR");
        }

        // Revalidate source wallet
        LedgerAccount sourceWallet = ledgerAccountRepository.findById(payout.getSourceLedgerAccountId())
                .orElseThrow(() -> new PayoutValidationException("Source wallet not found: " + payout.getSourceLedgerAccountId()));

        if (sourceWallet.getStatus() != AccountStatus.ACTIVE) {
            throw new PayoutValidationException("Source wallet is not active: " + sourceWallet.getId());
        }
        if (sourceWallet.getAccountType() != AccountType.CUSTOMER && sourceWallet.getAccountType() != AccountType.MERCHANT) {
            throw new PayoutValidationException("Source wallet must be CUSTOMER or MERCHANT: " + sourceWallet.getId());
        }
        if (!payout.getInitiatedByUserId().equals(sourceWallet.getOwnerUserId())) {
            throw new PayoutValidationException("Source wallet owner mismatch for payout: " + payout.getId());
        }
        if (!"INR".equals(sourceWallet.getCurrency())) {
            throw new PayoutValidationException("Source wallet currency must be INR: " + sourceWallet.getId());
        }

        // Resolve single active system PSP_CLEARING account
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

        // Deterministically lock snapshots in ascending order
        List<UUID> accountsToLock = List.of(payout.getSourceLedgerAccountId(), pspClearingAccount.getId());
        List<LedgerBalanceSnapshot> lockedSnapshots = ledgerBalanceSnapshotRepository.findAllByLedgerAccountIdInForUpdateOrdered(accountsToLock);
        if (lockedSnapshots.size() != 2) {
            throw new IllegalStateException("Expected 2 balance snapshots locked for settlement, but locked " + lockedSnapshots.size());
        }

        Instant now = Instant.now();

        // Post double-entry payout settlement journal: DEBIT source wallet, CREDIT PSP_CLEARING
        PostingLine debitWallet = PostingLine.debit(payout.getSourceLedgerAccountId(), payout.getAmountMinor());
        PostingLine creditClearing = PostingLine.credit(pspClearingAccount.getId(), payout.getAmountMinor());
        PostJournalCommand postCommand = new PostJournalCommand(List.of(debitWallet, creditClearing));

        PostingResult postingResult = ledgerPostingService.post(postCommand);

        // Consume the BalanceHold atomically
        hold.consume(now);
        balanceHoldRepository.saveAndFlush(hold);

        // Transition Payout to SUCCEEDED
        payout.markSucceeded(response.providerOperationId(), postingResult.journalTransactionId(), now);
        payoutRepository.saveAndFlush(payout);

        log.info("Committed payout settlement: payoutId={}, providerOpId={}, journalTxnId={}, amount={}",
                payout.getId(), response.providerOperationId(), postingResult.journalTransactionId(), payout.getAmountMinor());

        return toResult(payout, false);
    }

    private void validatePspResponse(Payout payout, PspOperationResponse response) {
        if (response.providerOperationId() == null) {
            throw new PayoutValidationException("PSP response missing providerOperationId");
        }
        if (!payout.getId().equals(response.clientOperationId())) {
            throw new PayoutValidationException("PSP clientOperationId " + response.clientOperationId() + " does not match payout id " + payout.getId());
        }
        if (!"DEBIT".equalsIgnoreCase(response.operationType())) {
            throw new PayoutValidationException("PSP operationType " + response.operationType() + " must be DEBIT");
        }
        if (!"SUCCEEDED".equalsIgnoreCase(response.status())) {
            throw new PayoutValidationException("PSP status " + response.status() + " is not SUCCEEDED");
        }
        if (!String.valueOf(payout.getAmountMinor()).equals(response.amountMinor())) {
            throw new PayoutValidationException("PSP amountMinor " + response.amountMinor() + " does not match payout amount " + payout.getAmountMinor());
        }
        if (!"INR".equalsIgnoreCase(response.currency())) {
            throw new PayoutValidationException("PSP currency " + response.currency() + " must be INR");
        }
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
