package com.ledgerguard.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity representing an idempotency record.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "operation", nullable = false, length = 64, updatable = false)
    private String operation;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IdempotencyStatus status;

    @Column(name = "result_id")
    private UUID resultId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
        // For JPA
    }

    private IdempotencyRecord(UUID id, UUID actorUserId, String operation, String idempotencyKey,
                              String requestFingerprint, IdempotencyStatus status, UUID resultId,
                              Instant createdAt, Instant completedAt) {
        this.id = id;
        this.actorUserId = actorUserId;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.status = status;
        this.resultId = resultId;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public static IdempotencyRecord createInProgress(UUID id, UUID actorUserId, String operation,
                                                     String idempotencyKey, String requestFingerprint,
                                                     Instant createdAt) {
        Objects.requireNonNull(id, "Record ID must not be null");
        Objects.requireNonNull(actorUserId, "Actor user ID must not be null");
        Objects.requireNonNull(operation, "Operation must not be null");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must not be null");
        Objects.requireNonNull(requestFingerprint, "Request fingerprint must not be null");
        Objects.requireNonNull(createdAt, "Created at timestamp must not be null");

        return new IdempotencyRecord(
                id,
                actorUserId,
                operation,
                idempotencyKey,
                requestFingerprint,
                IdempotencyStatus.IN_PROGRESS,
                null,
                createdAt,
                null
        );
    }

    public void complete(UUID resultId, Instant completedAt) {
        Objects.requireNonNull(resultId, "Result ID must not be null");
        Objects.requireNonNull(completedAt, "Completed at timestamp must not be null");

        if (this.status != IdempotencyStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete idempotency record in status " + this.status);
        }

        this.status = IdempotencyStatus.COMPLETED;
        this.resultId = resultId;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getOperation() {
        return operation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public UUID getResultId() {
        return resultId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
