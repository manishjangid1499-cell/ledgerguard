package com.ledgerguard.ledger.infrastructure;

import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerBalanceSnapshotRepository extends JpaRepository<LedgerBalanceSnapshot, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LedgerBalanceSnapshot s WHERE s.ledgerAccountId IN :accountIds ORDER BY s.ledgerAccountId ASC")
    List<LedgerBalanceSnapshot> findAllByLedgerAccountIdInForUpdateOrdered(@Param("accountIds") Collection<UUID> accountIds);
}
