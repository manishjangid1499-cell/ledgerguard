package com.ledgerguard.ledger.infrastructure;

import com.ledgerguard.ledger.domain.JournalStatus;
import com.ledgerguard.ledger.domain.JournalTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, UUID> {

    List<JournalTransaction> findByStatus(JournalStatus status);
}
