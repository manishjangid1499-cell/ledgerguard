package com.ledgerguard.transfer.application;

import com.ledgerguard.idempotency.application.IdempotencyCommand;
import com.ledgerguard.idempotency.application.IdempotencyExecutionResult;
import com.ledgerguard.idempotency.application.IdempotencyService;
import com.ledgerguard.idempotency.domain.RequestFingerprint;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.application.PostingResult;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.transfer.domain.Transfer;
import com.ledgerguard.transfer.domain.TransferDestinationNotFoundException;
import com.ledgerguard.transfer.domain.TransferValidationException;
import com.ledgerguard.transfer.infrastructure.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative application service for orchestrating atomic internal wallet transfers.
 * <p>
 * Combines authenticated actor validation, idempotency coordination, double-entry ledger posting,
 * automatic balance snapshot maintenance, and immutable transfer business record persistence
 * in a single atomic database transaction.
 */
@Service
public class TransferService {

    public static final String OPERATION_NAMESPACE = "internal-transfer:v1";

    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerPostingService ledgerPostingService;
    private final IdempotencyService idempotencyService;
    private final TransferRepository transferRepository;

    public TransferService(
            LedgerAccountRepository ledgerAccountRepository,
            LedgerPostingService ledgerPostingService,
            IdempotencyService idempotencyService,
            TransferRepository transferRepository
    ) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.idempotencyService = idempotencyService;
        this.transferRepository = transferRepository;
    }

    /**
     * Executes an internal transfer between user wallets idempotently and atomically.
     *
     * @param command validated transfer command
     * @return TransferResult containing transfer details and replay indicator
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public TransferResult createTransfer(CreateTransferCommand command) {
        Objects.requireNonNull(command, "CreateTransferCommand must not be null");

        // 1. Amount validation
        if (command.amount().getMinorUnits() <= 0) {
            throw new TransferValidationException("Transfer amount must be strictly positive: " + command.amount().getMinorUnits());
        }
        if (!"INR".equals(command.amount().getCurrencyCode())) {
            throw new TransferValidationException("Transfer currency must be INR: " + command.amount().getCurrencyCode());
        }

        // 2. Resolve and validate source wallet from authenticated actor
        List<LedgerAccount> sourceAccounts = ledgerAccountRepository.findByOwnerUserId(command.actorUserId());
        if (sourceAccounts.isEmpty()) {
            throw new TransferValidationException("No wallet account found for authenticated actor: " + command.actorUserId());
        }
        LedgerAccount sourceAccount = sourceAccounts.get(0);

        if (sourceAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new TransferValidationException("Source wallet account is not active: " + sourceAccount.getId() + " (status: " + sourceAccount.getStatus() + ")");
        }
        if (sourceAccount.getAccountType() != AccountType.CUSTOMER && sourceAccount.getAccountType() != AccountType.MERCHANT) {
            throw new TransferValidationException("Source account must be a user wallet: " + sourceAccount.getId());
        }
        if (!"INR".equals(sourceAccount.getCurrency())) {
            throw new TransferValidationException("Source wallet currency must be INR: " + sourceAccount.getCurrency());
        }

        // 3. Resolve and validate destination wallet
        LedgerAccount destinationAccount = ledgerAccountRepository.findById(command.destinationLedgerAccountId())
                .orElseThrow(() -> new TransferDestinationNotFoundException(command.destinationLedgerAccountId()));

        if (destinationAccount.getOwnerUserId() == null) {
            throw new TransferValidationException("Transfers to system ledger accounts are not permitted: " + destinationAccount.getId());
        }
        if (destinationAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new TransferValidationException("Destination wallet account is not active: " + destinationAccount.getId() + " (status: " + destinationAccount.getStatus() + ")");
        }
        if (destinationAccount.getAccountType() != AccountType.CUSTOMER && destinationAccount.getAccountType() != AccountType.MERCHANT) {
            throw new TransferValidationException("Destination account must be a user wallet: " + destinationAccount.getId());
        }
        if (!"INR".equals(destinationAccount.getCurrency())) {
            throw new TransferValidationException("Destination wallet currency must be INR: " + destinationAccount.getCurrency());
        }

        // 4. Self-transfer validation
        if (sourceAccount.getId().equals(destinationAccount.getId())) {
            throw new TransferValidationException("Self-transfers to the same ledger account are not permitted: " + sourceAccount.getId());
        }

        // 5. Deterministic canonical request fingerprint
        String canonicalPayload = String.format(
                "%s\nsource=%s\ndestination=%s\namountMinor=%d\ncurrency=%s",
                OPERATION_NAMESPACE,
                sourceAccount.getId(),
                destinationAccount.getId(),
                command.amount().getMinorUnits(),
                command.amount().getCurrencyCode()
        );
        RequestFingerprint fingerprint = RequestFingerprint.of(canonicalPayload);

        // 6. Idempotency coordination & atomic execution
        IdempotencyCommand idempotencyCommand = IdempotencyCommand.of(
                command.actorUserId(),
                OPERATION_NAMESPACE,
                command.idempotencyKey(),
                fingerprint
        );

        IdempotencyExecutionResult executionResult = idempotencyService.execute(idempotencyCommand, () -> {
            // A. Post balanced double-entry journal transaction
            PostingResult postingResult = ledgerPostingService.post(PostJournalCommand.of(
                    PostingLine.debit(sourceAccount.getId(), command.amount().getMinorUnits()),
                    PostingLine.credit(destinationAccount.getId(), command.amount().getMinorUnits())
            ));

            // B. Persist immutable transfer business record referencing posted journal
            UUID transferId = UUID.randomUUID();
            Transfer transfer = new Transfer(
                    transferId,
                    command.actorUserId(),
                    sourceAccount.getId(),
                    destinationAccount.getId(),
                    command.amount().getMinorUnits(),
                    command.amount().getCurrencyCode(),
                    postingResult.journalTransactionId(),
                    Instant.now()
            );
            transferRepository.saveAndFlush(transfer);

            return transferId;
        });

        // 7. Retrieve completed transfer record
        Transfer transfer = transferRepository.findById(executionResult.resultId())
                .orElseThrow(() -> new IllegalStateException("Transfer record not found: " + executionResult.resultId()));

        return new TransferResult(
                transfer.getId(),
                transfer.getSourceLedgerAccountId(),
                transfer.getDestinationLedgerAccountId(),
                transfer.getAmountMinor(),
                transfer.getCurrency(),
                transfer.getJournalTransactionId(),
                transfer.getCreatedAt(),
                executionResult.replayed()
        );
    }
}
