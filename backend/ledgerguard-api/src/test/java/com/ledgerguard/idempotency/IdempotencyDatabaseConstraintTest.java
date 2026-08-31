package com.ledgerguard.idempotency;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String VALID_FP = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    @DisplayName("Direct insertion of COMPLETED status is rejected by trigger")
    void directCompletedInsertIsRejected() {
        UUID actorId = createTestUser();
        UUID recordId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, result_id, created_at, completed_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-direct-completed', ?, 'COMPLETED', ?, ?, ?)",
                recordId, actorId, VALID_FP, resultId, now, now
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Idempotency records must be inserted with status IN_PROGRESS");
    }

    @Test
    @DisplayName("Direct insertion of IN_PROGRESS is allowed and transitioning to COMPLETED succeeds")
    void inProgressToCompletedAllowed() {
        UUID actorId = createTestUser();
        UUID recordId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Insert IN_PROGRESS
        int inserted = jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-in-progress-ok', ?, 'IN_PROGRESS', ?)",
                recordId, actorId, VALID_FP, now
        );
        assertThat(inserted).isEqualTo(1);

        // Update to COMPLETED
        int updated = jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', result_id = ?, completed_at = ? WHERE id = ?",
                resultId, now, recordId
        );
        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("Completed idempotency records cannot be transitioned back to IN_PROGRESS")
    void completedToInProgressRejected() {
        UUID actorId = createTestUser();
        UUID recordId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-reverse-status', ?, 'IN_PROGRESS', ?)",
                recordId, actorId, VALID_FP, now
        );
        jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', result_id = ?, completed_at = ? WHERE id = ?",
                resultId, now, recordId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'IN_PROGRESS', result_id = NULL, completed_at = NULL WHERE id = ?",
                recordId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Completed idempotency record");
    }

    @Test
    @DisplayName("Completed idempotency records are immutable against updates to result_id or metadata")
    void completedRecordUpdateRejected() {
        UUID actorId = createTestUser();
        UUID recordId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-immutable-test', ?, 'IN_PROGRESS', ?)",
                recordId, actorId, VALID_FP, now
        );
        jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', result_id = ?, completed_at = ? WHERE id = ?",
                resultId, now, recordId
        );

        UUID anotherResultId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET result_id = ? WHERE id = ?",
                anotherResultId, recordId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Completed idempotency record");
    }

    @Test
    @DisplayName("Completed idempotency records cannot be deleted")
    void completedRecordDeleteRejected() {
        UUID actorId = createTestUser();
        UUID recordId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-delete-test', ?, 'IN_PROGRESS', ?)",
                recordId, actorId, VALID_FP, now
        );
        jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', result_id = ?, completed_at = ? WHERE id = ?",
                resultId, now, recordId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM idempotency_records WHERE id = ?",
                recordId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Completed idempotency record");
    }

    @Test
    @DisplayName("Invalid fingerprint pattern is rejected by PostgreSQL check constraint")
    void invalidFingerprintRejected() {
        UUID actorId = createTestUser();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-bad-fp', 'not-a-valid-sha256-hex-hash', 'IN_PROGRESS', ?)",
                UUID.randomUUID(), actorId, now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Duplicate actor + operation + idempotency_key is rejected by unique constraint")
    void duplicateScopeRejected() {
        UUID actorId = createTestUser();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-dup-scope', ?, 'IN_PROGRESS', ?)",
                UUID.randomUUID(), actorId, VALID_FP, now
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-dup-scope', ?, 'IN_PROGRESS', ?)",
                UUID.randomUUID(), actorId, VALID_FP, now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("IN_PROGRESS record cannot have request_fingerprint mutated")
    void inProgressFingerprintMutationRejected() {
        UUID actorId = createTestUser();
        UUID recordId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-inprog-fp', ?, 'IN_PROGRESS', ?)",
                recordId, actorId, VALID_FP, now
        );

        String differentFp = "a3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET request_fingerprint = ? WHERE id = ?",
                differentFp, recordId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Invalid status transition");
    }

    @Test
    @DisplayName("IN_PROGRESS record cannot have actor_user_id, operation, idempotency_key, or created_at mutated")
    void inProgressIdentityMutationRejected() {
        UUID actorId = createTestUser();
        UUID otherActorId = createTestUser();
        UUID recordId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-inprog-ident', ?, 'IN_PROGRESS', ?)",
                recordId, actorId, VALID_FP, now
        );

        // Cannot mutate actor
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET actor_user_id = ? WHERE id = ?",
                otherActorId, recordId
        )).isInstanceOf(Exception.class);

        // Cannot mutate operation
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET operation = 'OTHER' WHERE id = ?",
                recordId
        )).isInstanceOf(Exception.class);

        // Cannot mutate key
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET idempotency_key = 'different-key' WHERE id = ?",
                recordId
        )).isInstanceOf(Exception.class);

        // Cannot mutate created_at
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET created_at = ? WHERE id = ?",
                Timestamp.from(now.toInstant().minusSeconds(100)), recordId
        )).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("IN_PROGRESS transition to COMPLETED is rejected if any identity field is altered")
    void inProgressToCompletedWithModifiedIdentityRejected() {
        UUID actorId = createTestUser();
        UUID otherActorId = createTestUser();
        UUID recordId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-inprog-trans-tamper', ?, 'IN_PROGRESS', ?)",
                recordId, actorId, VALID_FP, now
        );

        String differentFp = "a3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // Altered fingerprint on completion
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', result_id = ?, completed_at = ?, request_fingerprint = ? WHERE id = ?",
                resultId, now, differentFp, recordId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Immutable fields");

        // Altered actor on completion
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', result_id = ?, completed_at = ?, actor_user_id = ? WHERE id = ?",
                resultId, now, otherActorId, recordId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Immutable fields");

        // Altered operation on completion
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', result_id = ?, completed_at = ?, operation = 'OTHER' WHERE id = ?",
                resultId, now, recordId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Immutable fields");

        // Altered key on completion
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE idempotency_records SET status = 'COMPLETED', result_id = ?, completed_at = ?, idempotency_key = 'new-key' WHERE id = ?",
                resultId, now, recordId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("Immutable fields");
    }

    @Test
    @DisplayName("Foreign key constraint rejects nonexistent actor_user_id")
    void nonexistentActorRejected() {
        Timestamp now = Timestamp.from(Instant.now());
        UUID nonexistentUser = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO idempotency_records (id, actor_user_id, operation, idempotency_key, request_fingerprint, status, created_at) " +
                        "VALUES (?, ?, 'TRANSFER', 'key-bad-actor', ?, 'IN_PROGRESS', ?)",
                UUID.randomUUID(), nonexistentUser, VALID_FP, now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createTestUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'CUSTOMER', 'ACTIVE', ?, ?)",
                id, "idemp_test." + id + "@example.com", "$2a$10$dummyHashValueForTestingPurposeOnly", now, now
        );
        return id;
    }
}
