package com.ledgerguard.funding.infrastructure;

import com.ledgerguard.funding.domain.FundingOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisting and pessimistically locking FundingOperation entities.
 */
@Repository
public interface FundingOperationRepository extends JpaRepository<FundingOperation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FundingOperation f WHERE f.id = :id")
    Optional<FundingOperation> findByIdForUpdate(@Param("id") UUID id);

    Optional<FundingOperation> findByProviderOperationId(UUID providerOperationId);

    @Query(value = "SELECT id FROM funding_operations WHERE status IN ('PROCESSING', 'UNKNOWN') " +
            "AND next_provider_poll_at <= :now AND provider_poll_attempts >= :maxAttempts " +
            "ORDER BY next_provider_poll_at ASC FOR UPDATE SKIP LOCKED LIMIT :batchSize",
            nativeQuery = true)
    List<UUID> findExhaustedCandidateIdsForUpdate(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize);

    @Query(value = "SELECT id FROM funding_operations WHERE status IN ('PROCESSING', 'UNKNOWN') " +
            "AND next_provider_poll_at <= :now AND provider_poll_attempts < :maxAttempts " +
            "ORDER BY next_provider_poll_at ASC FOR UPDATE SKIP LOCKED LIMIT :batchSize",
            nativeQuery = true)
    List<UUID> findDueCandidateIdsForUpdate(
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize);
}
