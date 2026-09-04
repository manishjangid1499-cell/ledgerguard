package com.ledgerguard.reconciliation.infrastructure;

import com.ledgerguard.reconciliation.domain.ReconciliationCase;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationCaseRepository extends JpaRepository<ReconciliationCase, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ReconciliationCase c WHERE c.id = :id")
    Optional<ReconciliationCase> findByIdForUpdate(@Param("id") UUID id);

    Optional<ReconciliationCase> findByReconciliationItemId(UUID reconciliationItemId);

    @Query("SELECT c, i FROM ReconciliationCase c " +
           "JOIN ReconciliationItem i ON c.reconciliationItemId = i.id " +
           "WHERE (:status IS NULL OR c.status = :status) " +
           "AND (:level IS NULL OR i.level = :level) " +
           "AND (:classification IS NULL OR i.classification = :classification) " +
           "AND (:problemType IS NULL OR i.problemType = :problemType)")
    Page<Object[]> findCasesWithItemFiltered(
            @Param("status") ReconciliationCaseStatus status,
            @Param("level") ReconciliationLevel level,
            @Param("classification") ReconciliationClassification classification,
            @Param("problemType") ReconciliationProblemType problemType,
            Pageable pageable
    );
}
