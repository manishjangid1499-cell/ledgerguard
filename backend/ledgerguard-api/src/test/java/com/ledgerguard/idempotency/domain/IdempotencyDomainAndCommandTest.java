package com.ledgerguard.idempotency.domain;

import com.ledgerguard.idempotency.application.IdempotencyCommand;
import com.ledgerguard.idempotency.application.IdempotencyExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyDomainAndCommandTest {

    @Test
    @DisplayName("RequestFingerprint calculates deterministic SHA-256 lowercase hex string")
    void requestFingerprintDeterministic() {
        String input1 = "transfer:user1:user2:10000";
        RequestFingerprint fp1 = RequestFingerprint.of(input1);
        RequestFingerprint fp2 = RequestFingerprint.of(input1.getBytes(StandardCharsets.UTF_8));

        assertThat(fp1.getValue()).isEqualTo(fp2.getValue());
        assertThat(fp1.getValue()).hasSize(64);
        assertThat(fp1.getValue()).matches("^[0-9a-f]{64}$");

        String input2 = "transfer:user1:user2:10001";
        RequestFingerprint fp3 = RequestFingerprint.of(input2);
        assertThat(fp1.getValue()).isNotEqualTo(fp3.getValue());
    }

    @Test
    @DisplayName("RequestFingerprint.fromHex validates format")
    void requestFingerprintFromHexValidation() {
        String validHex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        RequestFingerprint fp = RequestFingerprint.fromHex(validHex);
        assertThat(fp.getValue()).isEqualTo(validHex);

        assertThatThrownBy(() -> RequestFingerprint.fromHex("invalid-hex"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be exactly 64 lowercase hexadecimal characters");

        assertThatThrownBy(() -> RequestFingerprint.fromHex("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IdempotencyCommand validates required fields and lengths")
    void idempotencyCommandValidation() {
        UUID actor = UUID.randomUUID();
        String validFp = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // Valid command
        IdempotencyCommand command = IdempotencyCommand.of(actor, "TRANSFER", "key-123", validFp);
        assertThat(command.actorUserId()).isEqualTo(actor);
        assertThat(command.operation()).isEqualTo("TRANSFER");
        assertThat(command.idempotencyKey()).isEqualTo("key-123");
        assertThat(command.requestFingerprint()).isEqualTo(validFp);

        // Null actor
        assertThatThrownBy(() -> IdempotencyCommand.of(null, "TRANSFER", "key-123", validFp))
                .isInstanceOf(NullPointerException.class);

        // Blank operation
        assertThatThrownBy(() -> IdempotencyCommand.of(actor, "   ", "key-123", validFp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Operation must not be blank");

        // Operation length > 64
        assertThatThrownBy(() -> IdempotencyCommand.of(actor, "A".repeat(65), "key-123", validFp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Operation length must not exceed 64");

        // Blank key
        assertThatThrownBy(() -> IdempotencyCommand.of(actor, "TRANSFER", "   ", validFp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency key must not be blank");

        // Key length > 128
        assertThatThrownBy(() -> IdempotencyCommand.of(actor, "TRANSFER", "K".repeat(129), validFp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency key length must not exceed 128");

        // Invalid fingerprint
        assertThatThrownBy(() -> IdempotencyCommand.of(actor, "TRANSFER", "key-123", "bad-fingerprint"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IdempotencyKey and operation are case-sensitive")
    void keyAndOperationCaseSensitivity() {
        UUID actor = UUID.randomUUID();
        String validFp = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        IdempotencyCommand upper = IdempotencyCommand.of(actor, "TRANSFER", "KEY_ABC", validFp);
        IdempotencyCommand lower = IdempotencyCommand.of(actor, "TRANSFER", "key_abc", validFp);

        assertThat(upper.idempotencyKey()).isEqualTo("KEY_ABC");
        assertThat(lower.idempotencyKey()).isEqualTo("key_abc");
        assertThat(upper.idempotencyKey()).isNotEqualTo(lower.idempotencyKey());
    }

    @Test
    @DisplayName("IdempotencyExecutionResult represents executed and replayed states")
    void executionResultStates() {
        UUID resultId = UUID.randomUUID();

        IdempotencyExecutionResult executed = IdempotencyExecutionResult.executed(resultId);
        assertThat(executed.resultId()).isEqualTo(resultId);
        assertThat(executed.replayed()).isFalse();

        IdempotencyExecutionResult replayed = IdempotencyExecutionResult.replayed(resultId);
        assertThat(replayed.resultId()).isEqualTo(resultId);
        assertThat(replayed.replayed()).isTrue();
    }

    @Test
    @DisplayName("IdempotencyRecord transitions from IN_PROGRESS to COMPLETED")
    void idempotencyRecordLifecycle() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String fp = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        Instant now = Instant.now();

        IdempotencyRecord record = IdempotencyRecord.createInProgress(id, actor, "TRANSFER", "key-1", fp, now);
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(record.getResultId()).isNull();
        assertThat(record.getCompletedAt()).isNull();

        UUID resultId = UUID.randomUUID();
        Instant completedAt = Instant.now();
        record.complete(resultId, completedAt);

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getResultId()).isEqualTo(resultId);
        assertThat(record.getCompletedAt()).isEqualTo(completedAt);

        // Cannot complete twice in entity
        assertThatThrownBy(() -> record.complete(resultId, completedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot complete idempotency record in status COMPLETED");
    }
}
