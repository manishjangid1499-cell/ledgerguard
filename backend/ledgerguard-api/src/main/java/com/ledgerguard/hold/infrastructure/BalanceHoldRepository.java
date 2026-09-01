package com.ledgerguard.hold.infrastructure;

import com.ledgerguard.hold.domain.BalanceHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BalanceHoldRepository extends JpaRepository<BalanceHold, UUID> {

    @Query("SELECT COALESCE(SUM(h.amountMinor), 0) FROM BalanceHold h WHERE h.ledgerAccountId = :ledgerAccountId AND h.status = com.ledgerguard.hold.domain.HoldStatus.ACTIVE")
    long sumActiveAmountByLedgerAccountId(@Param("ledgerAccountId") UUID ledgerAccountId);

    @Query("SELECT h FROM BalanceHold h WHERE h.status = com.ledgerguard.hold.domain.HoldStatus.ACTIVE AND h.expiresAt <= :now")
    List<BalanceHold> findDueActiveHolds(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM BalanceHold h WHERE h.id = :id")
    Optional<BalanceHold> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE BalanceHold h SET h.status = com.ledgerguard.hold.domain.HoldStatus.EXPIRED, h.terminalAt = :now, h.updatedAt = :now WHERE h.id = :id AND h.status = com.ledgerguard.hold.domain.HoldStatus.ACTIVE")
    int expireHoldConditional(@Param("id") UUID id, @Param("now") Instant now);

    List<BalanceHold> findAllByLedgerAccountId(UUID ledgerAccountId);
}
