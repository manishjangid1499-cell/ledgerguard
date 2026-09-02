package com.ledgerguard.provider.infrastructure;

import com.ledgerguard.provider.domain.ProviderEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProviderEventRepository extends JpaRepository<ProviderEvent, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO provider_events (
                event_id, provider_operation_id, client_operation_id, event_sequence,
                event_type, operation_type, provider_status, amount_minor, currency,
                occurred_at, payload, processing_status, received_at, processed_at
            ) VALUES (
                :eventId, :providerOperationId, :clientOperationId, :eventSequence,
                :eventType, :operationType, :providerStatus, :amountMinor, :currency,
                :occurredAt, CAST(:payload AS jsonb), 'PENDING', :receivedAt, NULL
            ) ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int tryInsertEvent(
            @Param("eventId") UUID eventId,
            @Param("providerOperationId") UUID providerOperationId,
            @Param("clientOperationId") UUID clientOperationId,
            @Param("eventSequence") long eventSequence,
            @Param("eventType") String eventType,
            @Param("operationType") String operationType,
            @Param("providerStatus") String providerStatus,
            @Param("amountMinor") long amountMinor,
            @Param("currency") String currency,
            @Param("occurredAt") Timestamp occurredAt,
            @Param("payload") String payload,
            @Param("receivedAt") Timestamp receivedAt
    );

    Optional<ProviderEvent> findByProviderOperationIdAndEventSequence(UUID providerOperationId, long eventSequence);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ProviderEvent e WHERE e.providerOperationId = :providerOperationId ORDER BY e.eventSequence ASC")
    List<ProviderEvent> findAllByProviderOperationIdForUpdate(@Param("providerOperationId") UUID providerOperationId);
}
