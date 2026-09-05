package com.ledgerguard.outbox.application;

import com.ledgerguard.outbox.domain.DomainEvent;
import com.ledgerguard.outbox.domain.OutboxEvent;
import com.ledgerguard.outbox.infrastructure.OutboxEventRepository;
import com.ledgerguard.shared.tracing.CorrelationIdFilter;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTraceContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Application service for appending domain events to the transactional outbox table.
 * All appends MUST execute within an existing caller database transaction (MANDATORY propagation).
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this(outboxEventRepository, objectMapper, null);
    }

    @Autowired
    public OutboxService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracerProvider
    ) {
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository, "outboxEventRepository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(DomainEvent event) {
        Objects.requireNonNull(event, "DomainEvent must not be null");

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(event.payload());
            JsonNode tree = objectMapper.readTree(jsonPayload);
            if (!tree.isObject()) {
                throw new IllegalArgumentException("Domain event payload must serialize to a JSON object: " + event.payload());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize domain event payload for eventId: " + event.eventId(), e);
        }

        TraceContextCapture traceContext = resolveTraceContext();
        String traceparent = traceContext.traceparent();
        String tracestate = traceContext.tracestate();
        String correlationId = resolveCorrelationId();

        OutboxEvent outboxEvent = OutboxEvent.pending(
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                jsonPayload,
                event.occurredAt(),
                Instant.now(),
                traceparent,
                tracestate,
                correlationId
        );

        outboxEventRepository.save(outboxEvent);
    }

    private TraceContextCapture resolveTraceContext() {
        try {
            if (tracer != null && tracer.currentSpan() != null && tracer.currentSpan().context() != null) {
                var micrometerCtx = tracer.currentSpan().context();
                io.opentelemetry.context.Context otelContext = OtelTraceContext.toOtelContext(micrometerCtx);
                if (otelContext == null) {
                    otelContext = io.opentelemetry.context.Context.current();
                }

                Map<String, String> carrier = new HashMap<>(2);
                W3CTraceContextPropagator.getInstance().inject(otelContext, carrier, Map::put);

                String traceparent = carrier.get("traceparent");
                String tracestate = carrier.get("tracestate");

                if (traceparent == null && micrometerCtx.traceId() != null && micrometerCtx.spanId() != null) {
                    boolean sampled = Boolean.TRUE.equals(micrometerCtx.sampled());
                    traceparent = String.format("00-%s-%s-%s", micrometerCtx.traceId(), micrometerCtx.spanId(), sampled ? "01" : "00");
                }
                return new TraceContextCapture(traceparent, tracestate);
            }
        } catch (Exception ignored) {
        }
        return new TraceContextCapture(null, null);
    }

    private record TraceContextCapture(String traceparent, String tracestate) {}

    private String resolveCorrelationId() {
        try {
            String mdcVal = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
            if (mdcVal != null && !mdcVal.isBlank()) {
                return mdcVal;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
