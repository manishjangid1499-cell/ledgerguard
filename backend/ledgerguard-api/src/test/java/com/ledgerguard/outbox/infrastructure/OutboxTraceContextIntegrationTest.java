package com.ledgerguard.outbox.infrastructure;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.outbox.domain.OutboxEvent;
import com.ledgerguard.outbox.domain.OutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 30 — Outbox Trace Context & Database Integrity Integration Tests")
class OutboxTraceContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Persists and reads back traceparent, tracestate, and correlationId on outbox_events")
    void persistsAndReadsBackTraceContext() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String tracestate = "congo=t61rcWkgMzE";
        String correlationId = "corr-" + UUID.randomUUID();

        OutboxEvent event = OutboxEvent.pending(
                eventId,
                "PAYMENT",
                aggregateId,
                "PAYMENT_SUCCEEDED",
                1,
                "{\"paymentId\":\"" + aggregateId + "\"}",
                Instant.now(),
                Instant.now(),
                traceparent,
                tracestate,
                correlationId
        );

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> repository.saveAndFlush(event));

        OutboxEvent loaded = repository.findById(eventId).orElseThrow();
        assertThat(loaded.getTraceparent()).isEqualTo(traceparent);
        assertThat(loaded.getTracestate()).isEqualTo(tracestate);
        assertThat(loaded.getCorrelationId()).isEqualTo(correlationId);
        assertThat(loaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("Trigger rejects UPDATE mutations to traceparent, tracestate, or correlation_id")
    void triggerEnforcesImmutabilityOfTraceContext() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String correlationId = "corr-immutable";

        OutboxEvent event = OutboxEvent.pending(
                eventId,
                "PAYMENT",
                aggregateId,
                "PAYMENT_SUCCEEDED",
                1,
                "{\"paymentId\":\"" + aggregateId + "\"}",
                Instant.now(),
                Instant.now(),
                traceparent,
                null,
                correlationId
        );

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> repository.saveAndFlush(event));

        // Attempting to modify traceparent on an existing row must be rejected by PostgreSQL trigger
        assertThatThrownBy(() -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE outbox_events SET traceparent = '00-altered-trace-id-12345' WHERE id = ?", eventId);
            });
        }).isInstanceOf(Exception.class)
          .hasMessageContaining("Outbox event data is immutable");

        // Attempting to modify correlation_id on an existing row must be rejected by PostgreSQL trigger
        assertThatThrownBy(() -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE outbox_events SET correlation_id = 'altered-correlation' WHERE id = ?", eventId);
            });
        }).isInstanceOf(Exception.class)
          .hasMessageContaining("Outbox event data is immutable");
    }

    @Test
    @DisplayName("Database check constraints reject oversized traceparent (>128) or correlation_id (>64)")
    void checkConstraintsRejectOversizedColumns() {
        UUID eventId1 = UUID.randomUUID();
        UUID aggId1 = UUID.randomUUID();
        String oversizedTraceparent = "0".repeat(129);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update(
                        "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, traceparent) " +
                        "VALUES (?, 'PAYMENT', ?, 'PAYMENT_SUCCEEDED', 1, '{}'::jsonb, 'PENDING', now(), now(), ?)",
                        eventId1, aggId1, oversizedTraceparent
                );
            });
        }).isInstanceOf(DataIntegrityViolationException.class);

        UUID eventId2 = UUID.randomUUID();
        UUID aggId2 = UUID.randomUUID();
        String oversizedCorrelationId = "c".repeat(65);

        assertThatThrownBy(() -> {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.update(
                        "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, correlation_id) " +
                        "VALUES (?, 'PAYMENT', ?, 'PAYMENT_SUCCEEDED', 1, '{}'::jsonb, 'PENDING', now(), now(), ?)",
                        eventId2, aggId2, oversizedCorrelationId
                );
            });
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}