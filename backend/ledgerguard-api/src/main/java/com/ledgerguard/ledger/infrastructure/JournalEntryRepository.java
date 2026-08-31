package com.ledgerguard.ledger.infrastructure;

import com.ledgerguard.ledger.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findByJournalTransactionId(UUID journalTransactionId);

    List<JournalEntry> findByLedgerAccountId(UUID ledgerAccountId);
}
