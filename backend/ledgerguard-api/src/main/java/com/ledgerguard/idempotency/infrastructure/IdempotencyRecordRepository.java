package com.ledgerguard.idempotency.infrastructure;

import com.ledgerguard.idempotency.domain.IdempotencyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for IdempotencyRecord entities.
 */
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    @Modifying
    @Query(value = """
        INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at)
        VALUES (:id, :actorUserId, :operation, :idempotencyKey, :requestFingerprint, 'IN_PROGRESS', :createdAt)
        ON CONFLICT (actor_user_id, operation, idempotency_key) DO NOTHING
    """, nativeQuery = true)
    int insertInProgressOnConflictDoNothing(
            @Param("id") UUID id,
            @Param("actorUserId") UUID actorUserId,
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestFingerprint") String requestFingerprint,
            @Param("createdAt") Instant createdAt
    );

    Optional<IdempotencyRecord> findByActorUserIdAndOperationAndIdempotencyKey(
            UUID actorUserId,
            String operation,
            String idempotencyKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT r FROM IdempotencyRecord r
        WHERE r.actorUserId = :actorUserId
          AND r.operation = :operation
          AND r.idempotencyKey = :idempotencyKey
    """)
    Optional<IdempotencyRecord> findByScopeForUpdate(
            @Param("actorUserId") UUID actorUserId,
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey
    );
}
