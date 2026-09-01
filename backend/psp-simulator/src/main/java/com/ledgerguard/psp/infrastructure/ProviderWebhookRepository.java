package com.ledgerguard.psp.infrastructure;

import com.ledgerguard.psp.domain.ProviderWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProviderWebhookRepository extends JpaRepository<ProviderWebhook, UUID> {

    @Query("SELECT w FROM ProviderWebhook w WHERE w.providerOperation.id = :providerOperationId")
    List<ProviderWebhook> findByProviderOperationId(@Param("providerOperationId") UUID providerOperationId);

    List<ProviderWebhook> findByEventId(UUID eventId);

    @Query(value = """
            SELECT * FROM provider_webhooks
            WHERE status = 'SCHEDULED' AND scheduled_at <= :now
            ORDER BY scheduled_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<ProviderWebhook> findDueWebhooksForUpdate(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
