package com.ledgerguard.provider.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "provider_events")
public class ProviderEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "provider_operation_id", nullable = false)
    private UUID providerOperationId;

    @Column(name = "client_operation_id", nullable = false)
    private UUID clientOperationId;

    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "operation_type", nullable = false, length = 32)
    private String operationType;

    @Column(name = "provider_status", nullable = false, length = 32)
    private String providerStatus;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 32)
    private ProviderProcessingStatus processingStatus;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected ProviderEvent() {
        // for JPA
    }

    public ProviderEvent(
            UUID eventId,
            UUID providerOperationId,
            UUID clientOperationId,
            long eventSequence,
            String eventType,
            String operationType,
            String providerStatus,
            long amountMinor,
            String currency,
            Instant occurredAt,
            String payload,
            Instant receivedAt
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId cannot be null");
        this.providerOperationId = Objects.requireNonNull(providerOperationId, "providerOperationId cannot be null");
        this.clientOperationId = Objects.requireNonNull(clientOperationId, "clientOperationId cannot be null");
        this.eventSequence = eventSequence;
        this.eventType = Objects.requireNonNull(eventType, "eventType cannot be null");
        this.operationType = Objects.requireNonNull(operationType, "operationType cannot be null");
        this.providerStatus = Objects.requireNonNull(providerStatus, "providerStatus cannot be null");
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        this.payload = Objects.requireNonNull(payload, "payload cannot be null");
        this.processingStatus = ProviderProcessingStatus.PENDING;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt cannot be null");
        this.processedAt = null;
    }

    public void markApplied(Instant at) {
        if (this.processingStatus != ProviderProcessingStatus.PENDING) {
            throw new IllegalStateException("Cannot transition non-PENDING event to APPLIED: " + this.processingStatus);
        }
        this.processingStatus = ProviderProcessingStatus.APPLIED;
        this.processedAt = Objects.requireNonNull(at, "processedAt cannot be null");
    }

    public void markIgnored(Instant at) {
        if (this.processingStatus != ProviderProcessingStatus.PENDING) {
            throw new IllegalStateException("Cannot transition non-PENDING event to IGNORED: " + this.processingStatus);
        }
        this.processingStatus = ProviderProcessingStatus.IGNORED;
        this.processedAt = Objects.requireNonNull(at, "processedAt cannot be null");
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getProviderOperationId() {
        return providerOperationId;
    }

    public UUID getClientOperationId() {
        return clientOperationId;
    }

    public long getEventSequence() {
        return eventSequence;
    }

    public String getEventType() {
        return eventType;
    }

    public String getOperationType() {
        return operationType;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getPayload() {
        return payload;
    }

    public ProviderProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
