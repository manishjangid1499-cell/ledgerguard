package com.ledgerguard.transfer.infrastructure;

import com.ledgerguard.transfer.domain.Transfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByJournalTransactionId(UUID journalTransactionId);

    @Query("SELECT t FROM Transfer t WHERE t.sourceLedgerAccountId = :walletId OR t.destinationLedgerAccountId = :walletId ORDER BY t.createdAt DESC, t.id DESC")
    Page<Transfer> findByWalletIdPaged(@Param("walletId") UUID walletId, Pageable pageable);
}
