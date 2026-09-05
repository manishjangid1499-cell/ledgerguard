package com.ledgerguard.notification;

import com.ledgerguard.notification.infrastructure.NotificationDeliveryRepository;
import com.ledgerguard.notification.infrastructure.ProcessedEventRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Phase 30 — Notification Worker Tracing Context Continuation Integration Tests")
class NotificationWorkerTracingIntegrationTest extends AbstractNotificationWorkerIntegrationTest {

    @Value("${ledgerguard.kafka.domain-events-topic:ledgerguard.domain-events.v1}")
    private String domainEventsTopic;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    private KafkaProducer<String, String> createTestProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    private String buildCloudEventJson(UUID eventId, String eventType, int version, String aggregateType, UUID aggregateId, String dataJson) {
        return String.format(
                "{\"specversion\":\"1.0\",\"id\":\"%s\",\"source\":\"urn:ledgerguard:ledgerguard-api\",\"type\":\"%s\",\"subject\":\"%s/%s\",\"time\":\"%s\",\"datacontenttype\":\"application/json\",\"eventversion\":%d,\"aggregatetype\":\"%s\",\"aggregateid\":\"%s\",\"data\":%s}",
                eventId, eventType, aggregateType, aggregateId, Instant.now(), version, aggregateType, aggregateId, dataJson
        );
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.ledgerguard.notification.application.NotificationDeliveryService deliveryService;

    @Test
    @DisplayName("Successfully consumes record with W3C traceparent and X-Correlation-Id headers")
    void consumesRecordWithTraceparentAndCorrelationHeaders() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String correlationId = "corr-worker-" + UUID.randomUUID();

        java.util.concurrent.atomic.AtomicReference<String> observedCorrelationId = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            observedCorrelationId.set(org.slf4j.MDC.get(com.ledgerguard.notification.application.DomainEventListener.MDC_CORRELATION_ID_KEY));
            return invocation.callRealMethod();
        }).when(deliveryService).recordDelivery(org.mockito.ArgumentMatchers.any());

        String data = String.format("{\"paymentId\":\"%s\",\"customerLedgerAccountId\":\"%s\",\"merchantLedgerAccountId\":\"%s\",\"grossAmountMinor\":\"5000\",\"feeAmountMinor\":\"100\",\"merchantNetAmountMinor\":\"4900\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                paymentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String message = buildCloudEventJson(eventId, "PAYMENT_SUCCEEDED", 1, "PAYMENT", paymentId, data);

        ProducerRecord<String, String> record = new ProducerRecord<>(
                domainEventsTopic,
                paymentId.toString(),
                message
        );
        record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        record.headers().add("X-Correlation-Id", correlationId.getBytes(StandardCharsets.UTF_8));

        try (KafkaProducer<String, String> producer = createTestProducer()) {
            producer.send(record);
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.findById(eventId)).isPresent();
            assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
            assertThat(observedCorrelationId.get()).isEqualTo(correlationId);
        });
        assertThat(org.slf4j.MDC.get(com.ledgerguard.notification.application.DomainEventListener.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Resilient against malformed traceparent and invalid correlation ID headers")
    void resilientAgainstMalformedHeaders() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String invalidCorrelationId = "invalid\r\nCRLF-injected-id";

        java.util.concurrent.atomic.AtomicReference<String> observedCorrelationId = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            observedCorrelationId.set(org.slf4j.MDC.get(com.ledgerguard.notification.application.DomainEventListener.MDC_CORRELATION_ID_KEY));
            return invocation.callRealMethod();
        }).when(deliveryService).recordDelivery(org.mockito.ArgumentMatchers.any());

        String data = String.format("{\"paymentId\":\"%s\",\"customerLedgerAccountId\":\"%s\",\"merchantLedgerAccountId\":\"%s\",\"grossAmountMinor\":\"5000\",\"feeAmountMinor\":\"100\",\"merchantNetAmountMinor\":\"4900\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                paymentId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String message = buildCloudEventJson(eventId, "PAYMENT_SUCCEEDED", 1, "PAYMENT", paymentId, data);

        ProducerRecord<String, String> record = new ProducerRecord<>(
                domainEventsTopic,
                paymentId.toString(),
                message
        );
        record.headers().add("traceparent", "malformed-garbage-trace-data".getBytes(StandardCharsets.UTF_8));
        record.headers().add("X-Correlation-Id", invalidCorrelationId.getBytes(StandardCharsets.UTF_8));

        try (KafkaProducer<String, String> producer = createTestProducer()) {
            producer.send(record);
            producer.flush();
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.findById(eventId)).isPresent();
            assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
            assertThat(observedCorrelationId.get()).isNotNull();
            assertThat(observedCorrelationId.get()).isNotEqualTo(invalidCorrelationId);
            assertThat(observedCorrelationId.get()).matches("^[0-9a-fA-F-]{36}$");
        });
        assertThat(org.slf4j.MDC.get(com.ledgerguard.notification.application.DomainEventListener.MDC_CORRELATION_ID_KEY)).isNull();
    }
}