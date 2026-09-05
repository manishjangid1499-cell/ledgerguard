package com.ledgerguard.outbox.application;

import com.ledgerguard.outbox.domain.DomainEventEnvelope;
import com.ledgerguard.outbox.domain.OutboxEvent;
import com.ledgerguard.outbox.infrastructure.OutboxEventRepository;
import com.ledgerguard.shared.tracing.CorrelationIdFilter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Transactional service for claiming and publishing pending outbox events to Kafka.
 */
@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);
    private static final String CONTENT_TYPE_HEADER = "content-type";
    private static final String CLOUDEVENTS_JSON_CONTENT_TYPE = "application/cloudevents+json";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topicName;
    private final long sendTimeoutMs;

    public OutboxPublisherService(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${ledgerguard.kafka.domain-events-topic:ledgerguard.domain-events.v1}") String topicName,
            @Value("${ledgerguard.outbox.publisher.send-timeout-ms:10000}") long sendTimeoutMs
    ) {
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository, "outboxEventRepository must not be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.topicName = Objects.requireNonNull(topicName, "topicName must not be null");
        this.sendTimeoutMs = sendTimeoutMs <= 0 ? 10000 : sendTimeoutMs;
    }

    /**
     * Claims a bounded batch of PENDING outbox events using FOR UPDATE SKIP LOCKED,
     * publishes each event to Kafka, waits for broker acknowledgment, and marks them PUBLISHED.
     *
     * @param batchSize the maximum number of events to claim in this batch
     * @return the number of events successfully published and marked PUBLISHED
     */
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_BATCH_SIZE = 500;

    @Transactional(propagation = Propagation.REQUIRED)
    public int publishPendingBatch(int batchSize) {
        int effectiveBatchSize = (batchSize <= 0) ? DEFAULT_BATCH_SIZE : Math.min(batchSize, MAX_BATCH_SIZE);
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEventsForPublishing(effectiveBatchSize);
        if (pendingEvents.isEmpty()) {
            return 0;
        }

        for (OutboxEvent event : pendingEvents) {
            publishSingleEvent(event);
        }

        return pendingEvents.size();
    }

    private static final TextMapGetter<OutboxEvent> OUTBOX_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(OutboxEvent carrier) {
            return List.of("traceparent", "tracestate");
        }

        @Override
        public String get(OutboxEvent carrier, String key) {
            if (carrier == null) return null;
            if ("traceparent".equalsIgnoreCase(key)) return carrier.getTraceparent();
            if ("tracestate".equalsIgnoreCase(key)) return carrier.getTracestate();
            return null;
        }
    };

    private void publishSingleEvent(OutboxEvent event) {
        String jsonPayload;
        try {
            DomainEventEnvelope envelope = DomainEventEnvelope.from(event, objectMapper);
            jsonPayload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to serialize outbox event envelope for event ID: {}", event.getId(), e);
            throw new OutboxPublishException("Failed to serialize outbox event " + event.getId(), e);
        }

        String messageKey = event.getAggregateId().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(topicName, messageKey, jsonPayload);
        record.headers().add(CONTENT_TYPE_HEADER, CLOUDEVENTS_JSON_CONTENT_TYPE.getBytes(StandardCharsets.UTF_8));

        String correlationId = event.getCorrelationId();
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        record.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));

        Context parentContext = Context.current();
        String traceparent = event.getTraceparent();
        if (traceparent != null && !traceparent.isBlank()) {
            try {
                parentContext = W3CTraceContextPropagator.getInstance().extract(Context.current(), event, OUTBOX_GETTER);
            } catch (Exception ignored) {
            }
        }

        String previousCorrelationId = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
        MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID_KEY, correlationId);

        try (Scope scope = parentContext.makeCurrent()) {
            if (traceparent != null && !traceparent.isBlank()) {
                record.headers().remove("traceparent");
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            }
            if (event.getTracestate() != null && !event.getTracestate().isBlank()) {
                record.headers().remove("tracestate");
                record.headers().add("tracestate", event.getTracestate().getBytes(StandardCharsets.UTF_8));
            }

            deduplicateHeader(record.headers(), "traceparent");
            deduplicateHeader(record.headers(), "tracestate");
            deduplicateHeader(record.headers(), CorrelationIdFilter.CORRELATION_ID_HEADER);

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);

            try {
                future.get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while awaiting Kafka broker acknowledgment for event ID: {}", event.getId());
                throw new OutboxPublishException("Interrupted awaiting Kafka ACK for event " + event.getId(), e);
            } catch (Exception e) {
                log.error("Kafka broker publication failed for event ID: {}, aggregate ID: {}, type: {}",
                        event.getId(), event.getAggregateId(), event.getEventType(), e);
                throw new OutboxPublishException("Kafka publish failed for event " + event.getId(), e);
            }
        } finally {
            if (previousCorrelationId != null) {
                MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID_KEY, previousCorrelationId);
            } else {
                MDC.remove(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
            }
        }

        event.markPublished(Instant.now());
        outboxEventRepository.saveAndFlush(event);
    }

    private void deduplicateHeader(Headers headers, String key) {
        Header last = headers.lastHeader(key);
        if (last != null) {
            byte[] value = last.value();
            headers.remove(key);
            headers.add(key, value);
        }
    }
}
