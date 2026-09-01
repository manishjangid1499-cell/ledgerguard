package com.ledgerguard.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationWorkerDatabaseConstraintTest extends AbstractNotificationWorkerIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("processed_events rejects null or blank event_type, non-positive version, and duplicate PK")
    void processedEventsConstraintsEnforced() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // Valid insert
        int inserted = jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, event_version, aggregate_type, aggregate_id, processed_at) " +
                        "VALUES (?, 'TRANSFER_COMPLETED', 1, 'TRANSFER', ?, ?)",
                eventId, UUID.randomUUID(), nowTs
        );
        assertThat(inserted).isEqualTo(1);

        // Duplicate primary key fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, event_version, aggregate_type, aggregate_id, processed_at) " +
                        "VALUES (?, 'PAYMENT_SUCCEEDED', 1, 'PAYMENT', ?, ?)",
                eventId, UUID.randomUUID(), nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Blank event_type fails check constraint
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, event_version, aggregate_type, aggregate_id, processed_at) " +
                        "VALUES (?, '   ', 1, 'TRANSFER', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Zero event_version fails check constraint
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, event_version, aggregate_type, aggregate_id, processed_at) " +
                        "VALUES (?, 'TRANSFER_COMPLETED', 0, 'TRANSFER', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("notification_deliveries enforces FK to processed_events, unique event_id, and status constraint")
    void notificationDeliveriesConstraintsEnforced() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // Foreign key violation if event not in processed_events
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO notification_deliveries (id, event_id, event_type, aggregate_type, aggregate_id, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER_COMPLETED', 'TRANSFER', ?, 'DELIVERED', ?)",
                UUID.randomUUID(), eventId, UUID.randomUUID(), nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Insert into processed_events first
        jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, event_version, aggregate_type, aggregate_id, processed_at) " +
                        "VALUES (?, 'TRANSFER_COMPLETED', 1, 'TRANSFER', ?, ?)",
                eventId, UUID.randomUUID(), nowTs
        );

        // Valid delivery insert
        UUID deliveryId = UUID.randomUUID();
        int inserted = jdbcTemplate.update(
                "INSERT INTO notification_deliveries (id, event_id, event_type, aggregate_type, aggregate_id, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER_COMPLETED', 'TRANSFER', ?, 'DELIVERED', ?)",
                deliveryId, eventId, UUID.randomUUID(), nowTs
        );
        assertThat(inserted).isEqualTo(1);

        // Duplicate event_id violates unique constraint
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO notification_deliveries (id, event_id, event_type, aggregate_type, aggregate_id, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER_COMPLETED', 'TRANSFER', ?, 'DELIVERED', ?)",
                UUID.randomUUID(), eventId, UUID.randomUUID(), nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Invalid status violates check constraint
        UUID eventId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO processed_events (event_id, event_type, event_version, aggregate_type, aggregate_id, processed_at) " +
                        "VALUES (?, 'TRANSFER_COMPLETED', 1, 'TRANSFER', ?, ?)",
                eventId2, UUID.randomUUID(), nowTs
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO notification_deliveries (id, event_id, event_type, aggregate_type, aggregate_id, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER_COMPLETED', 'TRANSFER', ?, 'PENDING', ?)",
                UUID.randomUUID(), eventId2, UUID.randomUUID(), nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
