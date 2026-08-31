package com.ledgerguard.transfer.infrastructure;

import com.ledgerguard.transfer.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByJournalTransactionId(UUID journalTransactionId);
}
