package com.ledgerguard.notification.infrastructure;

import com.ledgerguard.notification.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO processed_events (event_id, event_type, event_version, aggregate_type, aggregate_id, processed_at)
            VALUES (:eventId, :eventType, :eventVersion, :aggregateType, :aggregateId, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int tryInsertClaim(
            @Param("eventId") UUID eventId,
            @Param("eventType") String eventType,
            @Param("eventVersion") int eventVersion,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("processedAt") Instant processedAt
    );
}
