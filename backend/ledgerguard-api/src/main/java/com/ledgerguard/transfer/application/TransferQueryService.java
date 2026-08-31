package com.ledgerguard.transfer.application;

import com.ledgerguard.ledger.application.WalletQueryService;
import com.ledgerguard.ledger.domain.JournalEntry;
import com.ledgerguard.ledger.domain.JournalTransaction;
import com.ledgerguard.ledger.domain.Wallet;
import com.ledgerguard.ledger.infrastructure.JournalEntryRepository;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.shared.api.PagedResponse;
import com.ledgerguard.transfer.api.TransferDetailResponse;
import com.ledgerguard.transfer.api.TransferSummaryResponse;
import com.ledgerguard.transfer.domain.Transfer;
import com.ledgerguard.transfer.infrastructure.TransferRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only query service for retrieving authenticated user transfer history and double-entry journal details.
 */
@Service
public class TransferQueryService {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private final TransferRepository transferRepository;
    private final WalletQueryService walletQueryService;
    private final JournalTransactionRepository journalTransactionRepository;
    private final JournalEntryRepository journalEntryRepository;

    public TransferQueryService(
            TransferRepository transferRepository,
            WalletQueryService walletQueryService,
            JournalTransactionRepository journalTransactionRepository,
            JournalEntryRepository journalEntryRepository
    ) {
        this.transferRepository = transferRepository;
        this.walletQueryService = walletQueryService;
        this.journalTransactionRepository = journalTransactionRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    /**
     * Retrieves paginated transfer history for the authenticated user's wallet.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TransferSummaryResponse> findTransfersForUser(UUID actorUserId, Integer page, Integer size) {
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");

        Optional<Wallet> walletOpt = walletQueryService.findWalletByUserId(actorUserId);
        if (walletOpt.isEmpty()) {
            return new PagedResponse<>(Collections.emptyList(), 0, 0, 0L, 0);
        }

        UUID walletAccountId = walletOpt.get().ledgerAccountId();
        int resolvedPage = (page != null && page >= 0) ? page : DEFAULT_PAGE;
        int resolvedSize = (size != null && size > 0) ? Math.min(size, MAX_SIZE) : DEFAULT_SIZE;

        Page<Transfer> transferPage = transferRepository.findByWalletIdPaged(
                walletAccountId,
                PageRequest.of(resolvedPage, resolvedSize)
        );

        List<TransferSummaryResponse> items = transferPage.getContent().stream()
                .map(t -> {
                    String direction = t.getSourceLedgerAccountId().equals(walletAccountId) ? "OUTGOING" : "INCOMING";
                    return new TransferSummaryResponse(
                            t.getId(),
                            t.getSourceLedgerAccountId(),
                            t.getDestinationLedgerAccountId(),
                            String.valueOf(t.getAmountMinor()),
                            t.getCurrency(),
                            t.getJournalTransactionId(),
                            t.getCreatedAt(),
                            direction
                    );
                })
                .toList();

        return new PagedResponse<>(
                items,
                transferPage.getNumber(),
                transferPage.getSize(),
                transferPage.getTotalElements(),
                transferPage.getTotalPages()
        );
    }

    /**
     * Retrieves transfer detail including immutable journal transaction entries if the transfer is visible to the actor.
     */
    @Transactional(readOnly = true)
    public Optional<TransferDetailResponse> findTransferDetailForUser(UUID actorUserId, UUID transferId) {
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");
        Objects.requireNonNull(transferId, "Transfer ID must not be null");

        Optional<Wallet> walletOpt = walletQueryService.findWalletByUserId(actorUserId);
        if (walletOpt.isEmpty()) {
            return Optional.empty();
        }

        UUID walletAccountId = walletOpt.get().ledgerAccountId();
        Optional<Transfer> transferOpt = transferRepository.findById(transferId);
        if (transferOpt.isEmpty()) {
            return Optional.empty();
        }

        Transfer transfer = transferOpt.get();
        boolean isSource = transfer.getSourceLedgerAccountId().equals(walletAccountId);
        boolean isDestination = transfer.getDestinationLedgerAccountId().equals(walletAccountId);

        if (!isSource && !isDestination) {
            return Optional.empty(); // Actor is unrelated; return empty to yield 404
        }

        String direction = isSource ? "OUTGOING" : "INCOMING";

        // Resolve immutable journal transaction and entries
        JournalTransaction journal = journalTransactionRepository.findById(transfer.getJournalTransactionId())
                .orElseThrow(() -> new IllegalStateException("Journal transaction missing for transfer: " + transfer.getId()));

        List<JournalEntry> entries = journalEntryRepository.findByJournalTransactionId(journal.getId());

        List<TransferDetailResponse.JournalEntryDetailResponse> entryDetails = entries.stream()
                .map(e -> new TransferDetailResponse.JournalEntryDetailResponse(
                        e.getLedgerAccount().getId(),
                        e.getDirection().name(),
                        String.valueOf(e.getAmountMinor())
                ))
                .toList();

        TransferDetailResponse.JournalDetailResponse journalDetail = new TransferDetailResponse.JournalDetailResponse(
                journal.getId(),
                journal.getStatus().name(),
                journal.getPostedAt(),
                entryDetails
        );

        return Optional.of(new TransferDetailResponse(
                transfer.getId(),
                transfer.getSourceLedgerAccountId(),
                transfer.getDestinationLedgerAccountId(),
                String.valueOf(transfer.getAmountMinor()),
                transfer.getCurrency(),
                transfer.getJournalTransactionId(),
                transfer.getCreatedAt(),
                direction,
                journalDetail
        ));
    }
}
