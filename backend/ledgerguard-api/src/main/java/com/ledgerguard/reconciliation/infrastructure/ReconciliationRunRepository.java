package com.ledgerguard.reconciliation.infrastructure;

import com.ledgerguard.reconciliation.domain.ReconciliationRun;
import com.ledgerguard.reconciliation.domain.ReconciliationRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    /**
     * Acquires an exclusive row lock on the run for finalization.
     * Used by ReconciliationRunFinalizationService to serialize finalization
     * against concurrent item inserts.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReconciliationRun r WHERE r.id = :id")
    Optional<ReconciliationRun> findByIdForUpdate(@Param("id") UUID id);
}
