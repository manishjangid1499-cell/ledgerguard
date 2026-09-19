package com.ledgerguard.notification.infrastructure;

import com.ledgerguard.notification.domain.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    Optional<NotificationDelivery> findFirstByEventId(UUID eventId);

    default Optional<NotificationDelivery> findByEventId(UUID eventId) {
        return findFirstByEventId(eventId);
    }

    List<NotificationDelivery> findAllByEventId(UUID eventId);

    List<NotificationDelivery> findByAggregateId(UUID aggregateId);

    List<NotificationDelivery> findByStatus(String status);

    Optional<NotificationDelivery> findByEventIdAndRecipientEmailAndChannelAndTemplateKey(
            UUID eventId,
            String recipientEmail,
            String channel,
            String templateKey
    );

    @Query(value = """
            SELECT * FROM notification_deliveries
            WHERE (
                (status IN ('PENDING', 'RETRY_PENDING')
                 AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                 AND (locked_until IS NULL OR locked_until < :now))
                OR
                (status = 'SENDING' AND locked_until < :now)
            )
            ORDER BY next_attempt_at ASC NULLS FIRST, created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationDelivery> claimDueDeliveries(
            @Param("now") Instant now,
            @Param("limit") int limit
    );
}
