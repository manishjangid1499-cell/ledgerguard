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

    @Query(value = "SELECT h.* FROM balance_holds h WHERE h.status = 'ACTIVE' AND h.expires_at <= :now " +
            "AND NOT EXISTS (SELECT 1 FROM payouts p WHERE p.balance_hold_id = h.id AND p.status = 'PROCESSING')",
            nativeQuery = true)
    List<BalanceHold> findDueActiveHolds(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM BalanceHold h WHERE h.id = :id")
    Optional<BalanceHold> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query(value = "UPDATE balance_holds SET status = 'EXPIRED', terminal_at = :now, updated_at = :now " +
            "WHERE id = :id AND status = 'ACTIVE' " +
            "AND NOT EXISTS (SELECT 1 FROM payouts p WHERE p.balance_hold_id = balance_holds.id AND p.status = 'PROCESSING')",
            nativeQuery = true)
    int expireHoldConditional(@Param("id") UUID id, @Param("now") Instant now);

    List<BalanceHold> findAllByLedgerAccountId(UUID ledgerAccountId);
}
