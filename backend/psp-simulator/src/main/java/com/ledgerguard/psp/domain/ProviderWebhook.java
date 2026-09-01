package com.ledgerguard.psp.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_webhooks")
public class ProviderWebhook {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_operation_id", nullable = false)
    private ProviderOperation providerOperation;

    @Column(name = "delivery_number", nullable = false)
    private int deliveryNumber;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "target_url", nullable = false, columnDefinition = "text")
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WebhookStatus status;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProviderWebhook() {
        // JPA constructor
    }

    public ProviderWebhook(
            UUID id,
            UUID eventId,
            ProviderOperation providerOperation,
            int deliveryNumber,
            String eventType,
            String payload,
            String targetUrl,
            WebhookStatus status,
            Instant scheduledAt,
            Instant deliveredAt,
            Instant createdAt
    ) {
        this.id = id;
        this.eventId = eventId;
        this.providerOperation = providerOperation;
        this.deliveryNumber = deliveryNumber;
        this.eventType = eventType;
        this.payload = payload;
        this.targetUrl = targetUrl;
        this.status = status;
        this.scheduledAt = scheduledAt;
        this.deliveredAt = deliveredAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public ProviderOperation getProviderOperation() {
        return providerOperation;
    }

    public int getDeliveryNumber() {
        return deliveryNumber;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public WebhookStatus getStatus() {
        return status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markDelivered(Instant deliveredAt) {
        this.status = WebhookStatus.DELIVERED;
        this.deliveredAt = deliveredAt;
    }

    public void markFailed() {
        this.status = WebhookStatus.FAILED;
        this.deliveredAt = null;
    }
}
