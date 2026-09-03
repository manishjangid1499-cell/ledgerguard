package com.ledgerguard.payout.infrastructure;

import com.ledgerguard.payout.domain.Payout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payout p WHERE p.id = :id")
    Optional<Payout> findByIdForUpdate(@Param("id") UUID id);

    Optional<Payout> findByBalanceHoldId(UUID balanceHoldId);

    Optional<Payout> findByProviderOperationId(UUID providerOperationId);

    @Query(value = "SELECT id FROM payouts WHERE status IN ('PROCESSING', 'UNKNOWN') " +
            "AND next_provider_poll_at <= :now AND provider_poll_attempts >= :maxAttempts " +
            "ORDER BY next_provider_poll_at ASC FOR UPDATE SKIP LOCKED LIMIT :batchSize",
            nativeQuery = true)
    List<UUID> findExhaustedCandidateIdsForUpdate(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize);

    @Query(value = "SELECT id FROM payouts WHERE status IN ('PROCESSING', 'UNKNOWN') " +
            "AND next_provider_poll_at <= :now AND provider_poll_attempts < :maxAttempts " +
            "ORDER BY next_provider_poll_at ASC FOR UPDATE SKIP LOCKED LIMIT :batchSize",
            nativeQuery = true)
    List<UUID> findDueCandidateIdsForUpdate(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize);
}
