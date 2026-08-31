package com.ledgerguard.idempotency.application;

import com.ledgerguard.idempotency.domain.IdempotencyConflictException;
import com.ledgerguard.idempotency.domain.IdempotencyOperationInProgressException;
import com.ledgerguard.idempotency.domain.IdempotencyRecord;
import com.ledgerguard.idempotency.domain.IdempotencyStatus;
import com.ledgerguard.idempotency.infrastructure.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Core PostgreSQL-backed idempotency coordination service.
 * <p>
 * Ensures that for a given (actor, operation, idempotency_key, fingerprint), the underlying
 * transactional operation is executed at most once.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    /**
     * Executes the given operation idempotently within the current transaction boundary.
     *
     * @param command idempotency execution command
     * @param operation supplier yielding the stable committed result identifier
     * @return execution result indicating the result identifier and whether it was replayed
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public IdempotencyExecutionResult execute(IdempotencyCommand command, Supplier<UUID> operation) {
        Objects.requireNonNull(command, "Idempotency command must not be null");
        Objects.requireNonNull(operation, "Operation callback must not be null");

        UUID recordId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Attempt atomic claim via INSERT ... ON CONFLICT DO NOTHING
        int inserted = idempotencyRecordRepository.insertInProgressOnConflictDoNothing(
                recordId,
                command.actorUserId(),
                command.operation(),
                command.idempotencyKey(),
                command.requestFingerprint(),
                now
        );

        if (inserted == 1) {
            // 2A. Winner: owns execution inside this transaction
            UUID resultId = operation.get();
            if (resultId == null) {
                throw new IllegalStateException("Operation callback returned null result ID for idempotent operation: " + command.operation());
            }

            // Mark record COMPLETED in the same transaction
            IdempotencyRecord record = idempotencyRecordRepository.findById(recordId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency record not found after claim: " + recordId));
            record.complete(resultId, Instant.now());
            idempotencyRecordRepository.saveAndFlush(record);

            return IdempotencyExecutionResult.executed(resultId);
        }

        // 2B. Conflict: Slot already exists. Load with FOR UPDATE to coordinate with any in-flight winner.
        IdempotencyRecord existing = idempotencyRecordRepository.findByScopeForUpdate(
                command.actorUserId(),
                command.operation(),
                command.idempotencyKey()
        ).orElse(null);

        if (existing == null) {
            // Concurrent winner rolled back before committing: re-attempt claim
            return execute(command, operation);
        }

        // Validate request fingerprint match
        if (!existing.getRequestFingerprint().equals(command.requestFingerprint())) {
            throw new IdempotencyConflictException(
                    "Idempotency key '" + command.idempotencyKey() + "' was already used for operation '" +
                            command.operation() + "' with a different request fingerprint"
            );
        }

        // If completed, return cached result
        if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
            return IdempotencyExecutionResult.replayed(existing.getResultId());
        }

        // Record exists in IN_PROGRESS state
        throw new IdempotencyOperationInProgressException(
                "An operation with idempotency key '" + command.idempotencyKey() + "' is currently in progress for operation '" + command.operation() + "'"
        );
    }
}
