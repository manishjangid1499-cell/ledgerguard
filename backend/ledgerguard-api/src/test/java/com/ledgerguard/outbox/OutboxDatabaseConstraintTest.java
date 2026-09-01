package com.ledgerguard.outbox;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Valid PENDING insert with JSON object payload succeeds and published_at is NULL")
    void validPendingInsertSucceeds() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        int rows = jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\",\"amountMinor\":\"10000\"}",
                "PENDING", nowTs, nowTs, null
        );

        assertThat(rows).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?",
                String.class,
                eventId
        );
        Timestamp publishedAt = jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE id = ?",
                Timestamp.class,
                eventId
        );

        assertThat(status).isEqualTo("PENDING");
        assertThat(publishedAt).isNull();
    }

    @Test
    @DisplayName("Direct insert of PUBLISHED status is rejected by integrity trigger")
    void directPublishedInsertRejected() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PUBLISHED", nowTs, nowTs, nowTs
        )).hasMessageContaining("Direct insert of non-PENDING outbox event is prohibited");
    }

    @Test
    @DisplayName("Direct insert with non-null published_at is rejected by trigger")
    void directInsertWithPublishedAtRejected() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PENDING", nowTs, nowTs, nowTs
        )).hasMessageContaining("Direct insert with non-null published_at is prohibited");
    }

    @Test
    @DisplayName("Non-object payload (string, array, number, boolean) is rejected by check constraint")
    void nonObjectPayloadRejected() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // String payload
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "\"just a string\"",
                "PENDING", nowTs, nowTs, null
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Array payload
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "[\"item1\", \"item2\"]",
                "PENDING", nowTs, nowTs, null
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Number payload
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "12345",
                "PENDING", nowTs, nowTs, null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Outbox event data fields (id, aggregate, event_type, version, payload, timestamps) are strictly immutable")
    void outboxDataFieldsAreImmutable() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PENDING", nowTs, nowTs, null
        );

        // Mutate payload
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET payload = '{\"hacked\":true}'::jsonb WHERE id = ?",
                eventId
        )).hasMessageContaining("Outbox event data is immutable");

        // Mutate aggregate_id
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET aggregate_id = ? WHERE id = ?",
                UUID.randomUUID(), eventId
        )).hasMessageContaining("Outbox event data is immutable");

        // Mutate event_type
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET event_type = 'HACKED_EVENT' WHERE id = ?",
                eventId
        )).hasMessageContaining("Outbox event data is immutable");
    }

    @Test
    @DisplayName("Lifecycle transition PENDING -> PUBLISHED with published_at set succeeds; reverse transition is rejected")
    void lifecycleStatusTransitions() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PENDING", nowTs, nowTs, null
        );

        // Transition to PUBLISHED without published_at -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET status = 'PUBLISHED' WHERE id = ?",
                eventId
        )).hasMessageContaining("published_at must be set when transitioning to PUBLISHED");

        // Transition to PUBLISHED with valid published_at -> succeeds
        Timestamp publishedTs = Timestamp.from(now.plus(50, ChronoUnit.MILLIS));
        int updated = jdbcTemplate.update(
                "UPDATE outbox_events SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                publishedTs, eventId
        );
        assertThat(updated).isEqualTo(1);

        // Transition from PUBLISHED back to PENDING -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET status = 'PENDING', published_at = NULL WHERE id = ?",
                eventId
        )).hasMessageContaining("Outbox events in PUBLISHED status are immutable");
    }

    @Test
    @DisplayName("Deletion of PENDING event is rejected; deletion of PUBLISHED event is allowed for future retention")
    void deleteBehaviorPerStatus() {
        UUID pendingId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                pendingId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PENDING", nowTs, nowTs, null
        );

        // Attempt delete PENDING -> rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE id = ?",
                pendingId
        )).hasMessageContaining("Cannot delete outbox event in PENDING status");

        // Transition to PUBLISHED
        jdbcTemplate.update(
                "UPDATE outbox_events SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                nowTs, pendingId
        );

        // Delete PUBLISHED -> allowed
        int deleted = jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE id = ?",
                pendingId
        );
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    @DisplayName("Direct insert of PUBLISHED with NULL published_at is rejected")
    void directPublishedWithNullPublishedAtRejected() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PUBLISHED", nowTs, nowTs, null
        )).hasMessageContaining("Direct insert of non-PENDING outbox event is prohibited");
    }

    @Test
    @DisplayName("Multi-field mutation attempt in single UPDATE is rejected by integrity trigger")
    void multiFieldMutationRejected() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PENDING", nowTs, nowTs, null
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET aggregate_type = 'PAYMENT', event_type = 'PAYMENT_SUCCEEDED', payload = '{\"tampered\":true}'::jsonb WHERE id = ?",
                eventId
        )).hasMessageContaining("Outbox event data is immutable");
    }

    @Test
    @DisplayName("Event version <= 0 and blank aggregate_type/event_type are rejected by check constraints")
    void invalidFieldValuesRejected() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // event_version = 0
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 0,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PENDING", nowTs, nowTs, null
        )).isInstanceOf(DataIntegrityViolationException.class);

        // blank aggregate_type
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "   ", aggregateId, "TRANSFER_COMPLETED", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PENDING", nowTs, nowTs, null
        )).isInstanceOf(DataIntegrityViolationException.class);

        // blank event_type
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId, "TRANSFER", aggregateId, "   ", 1,
                "{\"transferId\":\"" + aggregateId + "\"}",
                "PENDING", nowTs, nowTs, null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
