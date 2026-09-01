package com.ledgerguard.outbox.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.outbox.domain.OutboxEvent;
import com.ledgerguard.outbox.domain.OutboxStatus;
import com.ledgerguard.outbox.infrastructure.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxPublisherFailureTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Kafka send failure throws OutboxPublishException and rolls back database transaction leaving event PENDING")
    void kafkaSendFailureRollsBackTransaction() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> failingKafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new org.apache.kafka.common.errors.TimeoutException("Kafka broker unavailable"));

        when(failingKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        OutboxPublisherService failingService = new OutboxPublisherService(
                outboxEventRepository,
                failingKafkaTemplate,
                objectMapper,
                "ledgerguard.domain-events.v1",
                5000
        );

        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, 'TRANSFER', ?, 'TRANSFER_COMPLETED', 1, '{\"transferId\":\"" + transferId + "\"}'::jsonb, 'PENDING', ?, ?, NULL)",
                eventId, transferId, nowTs, nowTs
        );

        assertThatThrownBy(() -> failingService.publishPendingBatch(10))
                .isInstanceOf(OutboxPublishException.class)
                .hasMessageContaining("Kafka publish failed");

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("Interrupted thread during send awaits restores interrupt flag and propagates exception")
    void interruptedThreadRestoresFlagAndThrows() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> hangingKafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> hangingFuture = new CompletableFuture<>();

        when(hangingKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(hangingFuture);

        OutboxPublisherService hangingService = new OutboxPublisherService(
                outboxEventRepository,
                hangingKafkaTemplate,
                objectMapper,
                "ledgerguard.domain-events.v1",
                5000
        );

        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, 'TRANSFER', ?, 'TRANSFER_COMPLETED', 1, '{\"transferId\":\"" + transferId + "\"}'::jsonb, 'PENDING', ?, ?, NULL)",
                eventId, transferId, nowTs, nowTs
        );

        // Pre-interrupt the thread
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> hangingService.publishPendingBatch(10))
                .isInstanceOf(OutboxPublishException.class)
                .hasMessageContaining("Interrupted");

        // Assert interrupted flag was restored
        assertThat(Thread.interrupted()).isTrue();

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();
    }
}
