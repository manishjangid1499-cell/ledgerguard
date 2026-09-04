package com.ledgerguard.reconciliation.infrastructure;

import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, UUID> {

    @Query("SELECT COUNT(i) FROM ReconciliationItem i WHERE i.reconciliationRunId = :runId AND i.classification = 'DISCREPANCY'")
    long countDiscrepanciesByRunId(@Param("runId") UUID runId);

    @Query("SELECT COUNT(i) FROM ReconciliationItem i WHERE i.reconciliationRunId = :runId AND i.classification = 'UNRESOLVED'")
    long countUnresolvedByRunId(@Param("runId") UUID runId);
}
