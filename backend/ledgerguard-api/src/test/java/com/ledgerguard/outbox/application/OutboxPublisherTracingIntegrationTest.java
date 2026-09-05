package com.ledgerguard.outbox.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.outbox.domain.OutboxEvent;
import com.ledgerguard.outbox.domain.OutboxStatus;
import com.ledgerguard.outbox.infrastructure.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 30 — Outbox Publisher Tracing & Correlation Header Propagation Integration Tests")
class OutboxPublisherTracingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${ledgerguard.kafka.domain-events-topic:ledgerguard.domain-events.v1}")
    private String domainEventsTopic;

    private KafkaConsumer<String, String> createTestConsumer() {
        var props = new java.util.Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-tracing-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(domainEventsTopic));
        return consumer;
    }

    @Test
    @DisplayName("Publishes Kafka record containing matching traceparent and X-Correlation-Id headers with no duplicates")
    void publishesMatchingTraceContextAndCorrelationHeaders() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String expectedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + expectedTraceId + "-00f067aa0ba902b7-01";
        String correlationId = "corr-test-" + UUID.randomUUID();

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
        tx.executeWithoutResult(status -> outboxEventRepository.saveAndFlush(event));

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            int publishedCount = outboxPublisherService.publishPendingBatch(10);
            assertThat(publishedCount).isGreaterThanOrEqualTo(1);

            List<ConsumerRecord<String, String>> matchingRecords = new ArrayList<>();
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (matchingRecords.isEmpty() && Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> r : polled) {
                    if (aggregateId.toString().equals(r.key())) {
                        matchingRecords.add(r);
                    }
                }
            }

            assertThat(matchingRecords).hasSize(1);
            ConsumerRecord<String, String> record = matchingRecords.get(0);

            // 1. Verify traceparent header exists and preserves originating traceId
            Header traceparentHeader = record.headers().lastHeader("traceparent");
            assertThat(traceparentHeader).isNotNull();
            String traceparentValue = new String(traceparentHeader.value(), StandardCharsets.UTF_8);
            assertThat(traceparentValue).contains(expectedTraceId);

            // 2. Verify X-Correlation-Id header exists and matches originating correlationId
            Header correlationHeader = record.headers().lastHeader("X-Correlation-Id");
            assertThat(correlationHeader).isNotNull();
            String correlationValue = new String(correlationHeader.value(), StandardCharsets.UTF_8);
            assertThat(correlationValue).isEqualTo(correlationId);

            // 3. Verify exactly 1 traceparent header (no duplicate injection)
            int traceparentHeaderCount = 0;
            for (Header h : record.headers().headers("traceparent")) {
                traceparentHeaderCount++;
            }
            assertThat(traceparentHeaderCount).isEqualTo(1);

            // 4. Verify exactly 1 X-Correlation-Id header (no duplicate injection)
            int correlationHeaderCount = 0;
            for (Header h : record.headers().headers("X-Correlation-Id")) {
                correlationHeaderCount++;
            }
            assertThat(correlationHeaderCount).isEqualTo(1);

            // 5. Verify exactly 0 tracestate headers when not present
            int tracestateHeaderCount = 0;
            for (Header h : record.headers().headers("tracestate")) {
                tracestateHeaderCount++;
            }
            assertThat(tracestateHeaderCount).isEqualTo(0);

            // 6. Verify database status updated to PUBLISHED
            OutboxEvent reloaded = outboxEventRepository.findById(eventId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(reloaded.getPublishedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("Publishes Kafka record generating fallback UUID X-Correlation-Id when stored correlation context is null")
    void generatesFallbackCorrelationIdWhenNull() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        OutboxEvent event = OutboxEvent.pending(
                eventId,
                "PAYMENT",
                aggregateId,
                "PAYMENT_SUCCEEDED",
                1,
                "{\"paymentId\":\"" + aggregateId + "\"}",
                Instant.now(),
                Instant.now(),
                null,
                null,
                null
        );

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> outboxEventRepository.saveAndFlush(event));

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            int publishedCount = outboxPublisherService.publishPendingBatch(10);
            assertThat(publishedCount).isGreaterThanOrEqualTo(1);

            List<ConsumerRecord<String, String>> matchingRecords = new ArrayList<>();
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (matchingRecords.isEmpty() && Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> r : polled) {
                    if (aggregateId.toString().equals(r.key())) {
                        matchingRecords.add(r);
                    }
                }
            }

            assertThat(matchingRecords).hasSize(1);
            ConsumerRecord<String, String> record = matchingRecords.get(0);

            Header correlationHeader = record.headers().lastHeader("X-Correlation-Id");
            assertThat(correlationHeader).isNotNull();
            String correlationValue = new String(correlationHeader.value(), StandardCharsets.UTF_8);
            assertThat(correlationValue).matches("^[0-9a-fA-F-]{36}$");
        }
    }

    @Test
    @DisplayName("Publishes Kafka record preserving tracestate and exactly 1 traceparent, 1 tracestate, and 1 X-Correlation-Id")
    void publishesMatchingTracestateHeaderWhenPresent() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String expectedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + expectedTraceId + "-00f067aa0ba902b7-01";
        String tracestate = "vendor=value123,rojo=1";
        String correlationId = "corr-tracestate-" + UUID.randomUUID();

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
        tx.executeWithoutResult(status -> outboxEventRepository.saveAndFlush(event));

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            int publishedCount = outboxPublisherService.publishPendingBatch(10);
            assertThat(publishedCount).isGreaterThanOrEqualTo(1);

            List<ConsumerRecord<String, String>> matchingRecords = new ArrayList<>();
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (matchingRecords.isEmpty() && Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> r : polled) {
                    if (aggregateId.toString().equals(r.key())) {
                        matchingRecords.add(r);
                    }
                }
            }

            assertThat(matchingRecords).hasSize(1);
            ConsumerRecord<String, String> record = matchingRecords.get(0);

            // Assert exactly 1 traceparent
            int traceparentCount = 0;
            for (Header h : record.headers().headers("traceparent")) traceparentCount++;
            assertThat(traceparentCount).isEqualTo(1);

            // Assert exactly 1 tracestate with exact value
            int tracestateCount = 0;
            for (Header h : record.headers().headers("tracestate")) tracestateCount++;
            assertThat(tracestateCount).isEqualTo(1);
            assertThat(new String(record.headers().lastHeader("tracestate").value(), StandardCharsets.UTF_8)).isEqualTo(tracestate);

            // Assert exactly 1 X-Correlation-Id
            int correlationCount = 0;
            for (Header h : record.headers().headers("X-Correlation-Id")) correlationCount++;
            assertThat(correlationCount).isEqualTo(1);
        }
    }
}