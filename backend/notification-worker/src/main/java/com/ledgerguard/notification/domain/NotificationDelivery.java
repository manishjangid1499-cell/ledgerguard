package com.ledgerguard.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "recipient_user_id")
    private UUID recipientUserId;

    @Column(name = "recipient_email", length = 255)
    private String recipientEmail;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel = "EMAIL";

    @Column(name = "template_key", length = 64)
    private String templateKey;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "locked_by", length = 128)
    private String lockedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationDelivery() {
    }

    /**
     * Full constructor.
     */
    public NotificationDelivery(
            UUID id,
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            UUID recipientUserId,
            String recipientEmail,
            String channel,
            String templateKey,
            String subject,
            String payloadJson,
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            String lastError,
            Instant sentAt,
            Instant lockedUntil,
            String lockedBy,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.recipientUserId = recipientUserId;
        this.recipientEmail = recipientEmail;
        this.channel = channel != null ? channel : "EMAIL";
        this.templateKey = templateKey;
        this.subject = subject;
        this.payloadJson = payloadJson;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
        this.sentAt = sentAt;
        this.lockedUntil = lockedUntil;
        this.lockedBy = lockedBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * Backward-compatible constructor.
     */
    public NotificationDelivery(UUID id, UUID eventId, String eventType, String aggregateType, UUID aggregateId, String status, Instant createdAt) {
        this(id, eventId, eventType, aggregateType, aggregateId, null, null, "EMAIL", null, null, null,
                status, 0, null, null, null, null, null, createdAt, createdAt);
    }

    public static NotificationDelivery pending(
            UUID id,
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            UUID recipientUserId,
            String recipientEmail,
            String channel,
            String templateKey,
            String subject,
            String payloadJson,
            Instant now
    ) {
        return new NotificationDelivery(
                id, eventId, eventType, aggregateType, aggregateId,
                recipientUserId, recipientEmail, channel, templateKey, subject, payloadJson,
                NotificationDeliveryStatus.PENDING.name(),
                0, now, null, null, null, null, now, now
        );
    }

    public static NotificationDelivery skipped(
            UUID id,
            UUID eventId,
            String eventType,
            String aggregateType,
            UUID aggregateId,
            UUID recipientUserId,
            String recipientEmail,
            String channel,
            String templateKey,
            String subject,
            String payloadJson,
            Instant now
    ) {
        return new NotificationDelivery(
                id, eventId, eventType, aggregateType, aggregateId,
                recipientUserId, recipientEmail, channel, templateKey, subject, payloadJson,
                NotificationDeliveryStatus.SKIPPED.name(),
                0, null, "Email delivery disabled", null, null, null, now, now
        );
    }

    public void claim(String workerId, Instant lockExpiry) {
        this.status = NotificationDeliveryStatus.SENDING.name();
        this.lockedBy = workerId;
        this.lockedUntil = lockExpiry;
        this.updatedAt = Instant.now();
    }

    public void markSent(Instant sentTimestamp) {
        this.status = NotificationDeliveryStatus.SENT.name();
        this.sentAt = sentTimestamp;
        this.attemptCount++;
        this.nextAttemptAt = null;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    public void markRetry(String error, Instant nextAttempt) {
        this.status = NotificationDeliveryStatus.RETRY_PENDING.name();
        this.attemptCount++;
        this.lastError = error;
        this.nextAttemptAt = nextAttempt;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = NotificationDeliveryStatus.FAILED.name();
        this.attemptCount++;
        this.lastError = error;
        this.nextAttemptAt = null;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public UUID getRecipientUserId() {
        return recipientUserId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getChannel() {
        return channel;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getSubject() {
        return subject;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
