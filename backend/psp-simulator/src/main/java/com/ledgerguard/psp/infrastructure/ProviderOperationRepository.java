package com.ledgerguard.psp.infrastructure;

import com.ledgerguard.psp.domain.ProviderOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

public interface ProviderOperationRepository extends JpaRepository<ProviderOperation, UUID> {

    Optional<ProviderOperation> findByClientOperationId(UUID clientOperationId);

    @Modifying
    @Query(value = """
            INSERT INTO provider_operations (
                id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at
            ) VALUES (
                :id, :clientOperationId, :operationType, :amountMinor, :currency, :status, :scenario, :createdAt, :completedAt
            ) ON CONFLICT (client_operation_id) DO NOTHING
            """, nativeQuery = true)
    int tryInsertOperation(
            @Param("id") UUID id,
            @Param("clientOperationId") UUID clientOperationId,
            @Param("operationType") String operationType,
            @Param("amountMinor") long amountMinor,
            @Param("currency") String currency,
            @Param("status") String status,
            @Param("scenario") String scenario,
            @Param("createdAt") Timestamp createdAt,
            @Param("completedAt") Timestamp completedAt
    );
}
