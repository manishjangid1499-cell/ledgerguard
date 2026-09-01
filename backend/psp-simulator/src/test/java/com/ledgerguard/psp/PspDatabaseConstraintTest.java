package com.ledgerguard.psp;

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

class PspDatabaseConstraintTest extends AbstractPspSimulatorIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("provider_operations enforces unique client_operation_id, amount > 0, currency = INR, valid enums, and completed_at")
    void providerOperationsConstraintsEnforced() {
        UUID opId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp nowTs = Timestamp.from(Instant.now());

        // Valid insert
        int inserted = jdbcTemplate.update(
                "INSERT INTO provider_operations (id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at) " +
                        "VALUES (?, ?, 'CREDIT', 10000, 'INR', 'SUCCEEDED', 'NORMAL_SUCCESS', ?, ?)",
                opId, clientOpId, nowTs, nowTs
        );
        assertThat(inserted).isEqualTo(1);

        // Duplicate client_operation_id fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_operations (id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at) " +
                        "VALUES (?, ?, 'DEBIT', 5000, 'INR', 'SUCCEEDED', 'NORMAL_SUCCESS', ?, ?)",
                UUID.randomUUID(), clientOpId, nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Non-positive amount fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_operations (id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at) " +
                        "VALUES (?, ?, 'CREDIT', 0, 'INR', 'SUCCEEDED', 'NORMAL_SUCCESS', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Invalid currency fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_operations (id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at) " +
                        "VALUES (?, ?, 'CREDIT', 10000, 'USD', 'SUCCEEDED', 'NORMAL_SUCCESS', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Invalid operation_type fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_operations (id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at) " +
                        "VALUES (?, ?, 'TRANSFER', 10000, 'INR', 'SUCCEEDED', 'NORMAL_SUCCESS', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Invalid scenario fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_operations (id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at) " +
                        "VALUES (?, ?, 'CREDIT', 10000, 'INR', 'SUCCEEDED', 'UNKNOWN_SCENARIO', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Null completed_at with SUCCEEDED status fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_operations (id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at) " +
                        "VALUES (?, ?, 'CREDIT', 10000, 'INR', 'SUCCEEDED', 'NORMAL_SUCCESS', ?, NULL)",
                UUID.randomUUID(), UUID.randomUUID(), nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("provider_webhooks enforces FK, positive delivery number, non-object JSON rejection, unique(event_id, delivery_number), and allows duplicate event_id with different delivery numbers")
    void providerWebhooksConstraintsEnforced() {
        UUID opId = UUID.randomUUID();
        UUID clientOpId = UUID.randomUUID();
        Timestamp nowTs = Timestamp.from(Instant.now());

        // Foreign key violation if operation does not exist
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_webhooks (id, event_id, provider_operation_id, delivery_number, event_type, payload, target_url, status, scheduled_at, delivered_at, created_at) " +
                        "VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', '{\"key\":\"val\"}'::jsonb, 'http://localhost/hook', 'SCHEDULED', ?, NULL, ?)",
                UUID.randomUUID(), UUID.randomUUID(), opId, nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Insert valid parent operation
        jdbcTemplate.update(
                "INSERT INTO provider_operations (id, client_operation_id, operation_type, amount_minor, currency, status, scenario, created_at, completed_at) " +
                        "VALUES (?, ?, 'CREDIT', 10000, 'INR', 'SUCCEEDED', 'NORMAL_SUCCESS', ?, ?)",
                opId, clientOpId, nowTs, nowTs
        );

        UUID webhook1Id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // Valid insert
        int inserted = jdbcTemplate.update(
                "INSERT INTO provider_webhooks (id, event_id, provider_operation_id, delivery_number, event_type, payload, target_url, status, scheduled_at, delivered_at, created_at) " +
                        "VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', '{\"key\":\"val\"}'::jsonb, 'http://localhost/hook', 'SCHEDULED', ?, NULL, ?)",
                webhook1Id, eventId, opId, nowTs, nowTs
        );
        assertThat(inserted).isEqualTo(1);

        // Duplicate (event_id, delivery_number) fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_webhooks (id, event_id, provider_operation_id, delivery_number, event_type, payload, target_url, status, scheduled_at, delivered_at, created_at) " +
                        "VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', '{\"key\":\"val\"}'::jsonb, 'http://localhost/hook', 'SCHEDULED', ?, NULL, ?)",
                UUID.randomUUID(), eventId, opId, nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Non-positive delivery_number fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_webhooks (id, event_id, provider_operation_id, delivery_number, event_type, payload, target_url, status, scheduled_at, delivered_at, created_at) " +
                        "VALUES (?, ?, ?, 0, 'PROVIDER_OPERATION_SUCCEEDED', '{\"key\":\"val\"}'::jsonb, 'http://localhost/hook', 'SCHEDULED', ?, NULL, ?)",
                UUID.randomUUID(), UUID.randomUUID(), opId, nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Non-object JSON payload fails
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO provider_webhooks (id, event_id, provider_operation_id, delivery_number, event_type, payload, target_url, status, scheduled_at, delivered_at, created_at) " +
                        "VALUES (?, ?, ?, 1, 'PROVIDER_OPERATION_SUCCEEDED', '\"not_an_object\"'::jsonb, 'http://localhost/hook', 'SCHEDULED', ?, NULL, ?)",
                UUID.randomUUID(), UUID.randomUUID(), opId, nowTs, nowTs
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Duplicate event_id with different delivery_number (2) SUCCEEDS (required for DUPLICATE_WEBHOOK scenario)
        UUID webhook2Id = UUID.randomUUID();
        int inserted2 = jdbcTemplate.update(
                "INSERT INTO provider_webhooks (id, event_id, provider_operation_id, delivery_number, event_type, payload, target_url, status, scheduled_at, delivered_at, created_at) " +
                        "VALUES (?, ?, ?, 2, 'PROVIDER_OPERATION_SUCCEEDED', '{\"key\":\"val\"}'::jsonb, 'http://localhost/hook', 'SCHEDULED', ?, NULL, ?)",
                webhook2Id, eventId, opId, nowTs, nowTs
        );
        assertThat(inserted2).isEqualTo(1);
    }
}
