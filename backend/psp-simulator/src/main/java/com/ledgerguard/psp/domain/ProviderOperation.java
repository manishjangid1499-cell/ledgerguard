package com.ledgerguard.psp.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_operations")
public class ProviderOperation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "client_operation_id", nullable = false, unique = true)
    private UUID clientOperationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 16)
    private OperationType operationType;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OperationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario", nullable = false, length = 32)
    private SimulatorScenario scenario;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ProviderOperation() {
        // JPA constructor
    }

    public ProviderOperation(
            UUID id,
            UUID clientOperationId,
            OperationType operationType,
            long amountMinor,
            String currency,
            OperationStatus status,
            SimulatorScenario scenario,
            Instant createdAt,
            Instant completedAt
    ) {
        this.id = id;
        this.clientOperationId = clientOperationId;
        this.operationType = operationType;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.scenario = scenario;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClientOperationId() {
        return clientOperationId;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public OperationStatus getStatus() {
        return status;
    }

    public SimulatorScenario getScenario() {
        return scenario;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
