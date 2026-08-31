package com.ledgerguard.ledger.application;

import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.JournalEntry;
import com.ledgerguard.ledger.domain.JournalTransaction;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.JournalEntryRepository;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Authoritative application service for executing atomic, balanced double-entry journal postings.
 * Ensures the complete lifecycle (DRAFT creation -> entry persistence -> balance validation -> POSTED transition)
 * executes inside a single database transaction.
 */
@Service
public class LedgerPostingService {

    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalTransactionRepository journalTransactionRepository;
    private final JournalEntryRepository journalEntryRepository;

    public LedgerPostingService(
            LedgerAccountRepository ledgerAccountRepository,
            JournalTransactionRepository journalTransactionRepository,
            JournalEntryRepository journalEntryRepository
    ) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.journalTransactionRepository = journalTransactionRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    /**
     * Atomically creates and posts a complete balanced double-entry journal transaction.
     *
     * @param command immutable validated posting command containing posting lines
     * @return PostingResult containing the journal transaction ID and posting timestamp
     * @throws LedgerPostingException if validation fails or referenced accounts are invalid/inactive
     */
    @Transactional
    public PostingResult post(PostJournalCommand command) {
        Objects.requireNonNull(command, "PostJournalCommand must not be null");

        // 1. Resolve and validate all unique referenced ledger accounts
        Set<UUID> requiredAccountIds = command.lines().stream()
                .map(PostingLine::ledgerAccountId)
                .collect(Collectors.toSet());

        List<LedgerAccount> accounts = ledgerAccountRepository.findAllById(requiredAccountIds);
        Map<UUID, LedgerAccount> accountMap = accounts.stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));

        if (accountMap.size() < requiredAccountIds.size()) {
            Set<UUID> missing = new HashSet<>(requiredAccountIds);
            missing.removeAll(accountMap.keySet());
            throw new LedgerPostingException("Referenced ledger account(s) not found: " + missing);
        }

        for (LedgerAccount account : accounts) {
            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new LedgerPostingException(
                        "Cannot post to inactive ledger account: " + account.getId() + " (status: " + account.getStatus() + ")");
            }
            if (!"INR".equals(account.getCurrency())) {
                throw new LedgerPostingException(
                        "Account currency mismatch for account " + account.getId() + ": expected INR, found " + account.getCurrency());
            }
        }

        // 2. Create DRAFT journal transaction and persist
        JournalTransaction journalTransaction = JournalTransaction.createDraft();
        journalTransaction = journalTransactionRepository.saveAndFlush(journalTransaction);

        // 3. Create and persist all journal entries associated with the DRAFT journal
        List<JournalEntry> entries = new ArrayList<>(command.lines().size());
        for (PostingLine line : command.lines()) {
            LedgerAccount account = accountMap.get(line.ledgerAccountId());
            JournalEntry entry = new JournalEntry(
                    UUID.randomUUID(),
                    journalTransaction,
                    account,
                    line.direction(),
                    line.amount().getMinorUnits()
            );
            entries.add(entry);
        }
        journalEntryRepository.saveAllAndFlush(entries);

        // 4. Transition journal from DRAFT to POSTED and flush to trigger authoritative DB balance enforcement
        journalTransaction.post(Instant.now());
        journalTransaction = journalTransactionRepository.saveAndFlush(journalTransaction);

        return new PostingResult(journalTransaction.getId(), journalTransaction.getPostedAt());
    }
}
