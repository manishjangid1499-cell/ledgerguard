package com.ledgerguard.provider;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderEventDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String VALID_PAYLOAD = "{\"key\": \"value\"}";

    @Test
    @DisplayName("V12 accepts valid PENDING provider_event insert with null processed_at")
    void validPendingInsertAccepted() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        int rows = jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now);

        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("V12 rejects event_sequence <= 0")
    void rejectsZeroOrNegativeSequence() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 0, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now))
                .hasMessageContaining("chk_provider_events_sequence");
    }

    @Test
    @DisplayName("V12 rejects non-positive amount_minor")
    void rejectsNonPositiveAmount() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 0, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now))
                .hasMessageContaining("chk_provider_events_amount");
    }

    @Test
    @DisplayName("V12 rejects currency != 'INR'")
    void rejectsInvalidCurrency() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'USD', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now))
                .hasMessageContaining("chk_provider_events_currency");
    }

    @Test
    @DisplayName("V12 rejects invalid operation_type")
    void rejectsInvalidOperationType() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'TRANSFER', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now))
                .hasMessageContaining("chk_provider_events_operation_type");
    }

    @Test
    @DisplayName("V12 rejects event_type and provider_status mismatch")
    void rejectsTypeStatusMismatch() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'FAILED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now))
                .hasMessageContaining("chk_provider_events_type_status_match");
    }

    @Test
    @DisplayName("V12 rejects non-object JSON payload")
    void rejectsNonObjectJsonPayload() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, '\"just a string\"'::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, now))
                .hasMessageContaining("chk_provider_events_payload_json");
    }

    @Test
    @DisplayName("V12 trigger rejects direct insert with processing_status = APPLIED")
    void rejectsDirectAppliedInsert() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'APPLIED', ?, ?)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now, now))
                .hasMessageContaining("ProviderEvent insert must have processing_status = PENDING");
    }

    @Test
    @DisplayName("V12 trigger rejects direct insert with processing_status = IGNORED")
    void rejectsDirectIgnoredInsert() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'IGNORED', ?, ?)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now, now))
                .hasMessageContaining("ProviderEvent insert must have processing_status = PENDING");
    }

    @Test
    @DisplayName("V12 trigger rejects insert with non-null processed_at even when PENDING")
    void rejectsNonNullProcessedAtOnInsert() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, ?)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now, now))
                .hasMessageContaining("ProviderEvent insert must have processed_at IS NULL");
    }

    @Test
    @DisplayName("V12 trigger rejects UPDATE of immutable business columns")
    void rejectsBusinessContentMutation() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE provider_events SET amount_minor = 2000 WHERE event_id = ?",
                eventId))
                .hasMessageContaining("ProviderEvent business content is immutable");
    }

    @Test
    @DisplayName("V12 trigger allows valid PENDING -> APPLIED transition with processed_at")
    void allowsPendingToAppliedTransition() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now);

        Timestamp processedAt = Timestamp.from(Instant.now());
        int updated = jdbcTemplate.update(
                "UPDATE provider_events SET processing_status = 'APPLIED', processed_at = ? WHERE event_id = ?",
                processedAt, eventId
        );
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("V12 trigger allows valid PENDING -> IGNORED transition with processed_at")
    void allowsPendingToIgnoredTransition() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now);

        Timestamp processedAt = Timestamp.from(Instant.now());
        int updated = jdbcTemplate.update(
                "UPDATE provider_events SET processing_status = 'IGNORED', processed_at = ? WHERE event_id = ?",
                processedAt, eventId
        );
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("V12 trigger rejects transition out of terminal status APPLIED")
    void rejectsTerminalStatusTransitionFromApplied() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now);

        jdbcTemplate.update(
                "UPDATE provider_events SET processing_status = 'APPLIED', processed_at = ? WHERE event_id = ?",
                now, eventId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE provider_events SET processing_status = 'PENDING', processed_at = NULL WHERE event_id = ?",
                eventId))
                .hasMessageContaining("ProviderEvent terminal status APPLIED cannot be modified");
    }

    @Test
    @DisplayName("V12 trigger rejects DELETE")
    void rejectsDelete() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId, clientOpId, now, VALID_PAYLOAD, now);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM provider_events WHERE event_id = ?", eventId))
                .hasMessageContaining("ProviderEvent deletion is strictly forbidden");
    }

    @Test
    @DisplayName("V12 rejects duplicate event_id PK")
    void rejectsDuplicateEventId() {
        UUID eventId = UUID.randomUUID();
        UUID providerOpId1 = UUID.randomUUID();
        UUID providerOpId2 = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId1, clientOpId, now, VALID_PAYLOAD, now);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 2, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId, providerOpId2, clientOpId, now, VALID_PAYLOAD, now))
                .hasMessageContaining("provider_events_pkey");
    }

    @Test
    @DisplayName("V12 rejects duplicate (provider_operation_id, event_sequence)")
    void rejectsDuplicateProviderOperationIdAndSequence() {
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId1, providerOpId, clientOpId, now, VALID_PAYLOAD, now);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO provider_events (
                    event_id, provider_operation_id, client_operation_id, event_sequence,
                    event_type, operation_type, provider_status, amount_minor, currency,
                    occurred_at, payload, processing_status, received_at, processed_at
                ) VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', 'CREDIT', 'SUCCEEDED', 1000, 'INR', ?, ?::jsonb, 'PENDING', ?, NULL)
                """, eventId2, providerOpId, clientOpId, now, VALID_PAYLOAD, now))
                .hasMessageContaining("uq_provider_events_op_seq");
    }
}
