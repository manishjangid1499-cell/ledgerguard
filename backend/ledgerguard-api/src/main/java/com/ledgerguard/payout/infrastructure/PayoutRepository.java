package com.ledgerguard.payout.infrastructure;

import com.ledgerguard.payout.domain.Payout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payout p WHERE p.id = :id")
    Optional<Payout> findByIdForUpdate(@Param("id") UUID id);

    Optional<Payout> findByBalanceHoldId(UUID balanceHoldId);
}
