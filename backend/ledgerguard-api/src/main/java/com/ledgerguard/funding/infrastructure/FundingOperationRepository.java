package com.ledgerguard.funding.infrastructure;

import com.ledgerguard.funding.domain.FundingOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisting and pessimistically locking FundingOperation entities.
 */
@Repository
public interface FundingOperationRepository extends JpaRepository<FundingOperation, UUID> {

    /**
     * Acquires a pessimistic write row lock on the specified FundingOperation.
     *
     * @param id funding operation ID
     * @return optional containing the locked FundingOperation if present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM FundingOperation f WHERE f.id = :id")
    Optional<FundingOperation> findByIdForUpdate(@Param("id") UUID id);
}
