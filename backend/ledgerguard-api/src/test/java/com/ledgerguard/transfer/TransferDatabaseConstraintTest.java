package com.ledgerguard.transfer;

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

class TransferDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Transfer referencing a DRAFT journal transaction is rejected by trigger")
    void transferReferencingDraftJournalIsRejected() {
        UUID actorId = createTestUser();
        UUID sourceAcc = createTestAccount(actorId, "CUSTOMER");
        UUID destActorId = createTestUser();
        UUID destAcc = createTestAccount(destActorId, "CUSTOMER");
        UUID draftJournalId = createTestDraftJournal();
        UUID transferId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', ?, ?)",
                transferId, actorId, sourceAcc, destAcc, draftJournalId, now
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("must be POSTED");
    }

    @Test
    @DisplayName("Direct UPDATE on transfers is rejected by trigger")
    void directUpdateIsRejected() {
        UUID actorId = createTestUser();
        UUID sourceAcc = createTestAccount(actorId, "CUSTOMER");
        UUID destActorId = createTestUser();
        UUID destAcc = createTestAccount(destActorId, "CUSTOMER");
        UUID journalId = createTestPostedJournal(sourceAcc, destAcc, 10000);
        UUID transferId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', ?, ?)",
                transferId, actorId, sourceAcc, destAcc, journalId, now
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE transfers SET amount_minor = 20000 WHERE id = ?",
                transferId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("immutable and cannot be updated");
    }

    @Test
    @DisplayName("Direct DELETE on transfers is rejected by trigger")
    void directDeleteIsRejected() {
        UUID actorId = createTestUser();
        UUID sourceAcc = createTestAccount(actorId, "CUSTOMER");
        UUID destActorId = createTestUser();
        UUID destAcc = createTestAccount(destActorId, "CUSTOMER");
        UUID journalId = createTestPostedJournal(sourceAcc, destAcc, 10000);
        UUID transferId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', ?, ?)",
                transferId, actorId, sourceAcc, destAcc, journalId, now
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM transfers WHERE id = ?",
                transferId
        )).isInstanceOf(Exception.class)
                .hasMessageContaining("immutable and cannot be deleted");
    }

    @Test
    @DisplayName("Zero or negative amount is rejected by check constraint")
    void nonPositiveAmountRejected() {
        UUID actorId = createTestUser();
        UUID sourceAcc = createTestAccount(actorId, "CUSTOMER");
        UUID destActorId = createTestUser();
        UUID destAcc = createTestAccount(destActorId, "CUSTOMER");
        UUID journalId = createTestPostedJournal(sourceAcc, destAcc, 10000);
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 0, 'INR', ?, ?)",
                UUID.randomUUID(), actorId, sourceAcc, destAcc, journalId, now
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, -500, 'INR', ?, ?)",
                UUID.randomUUID(), actorId, sourceAcc, destAcc, journalId, now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Non-INR currency is rejected by check constraint")
    void nonInrCurrencyRejected() {
        UUID actorId = createTestUser();
        UUID sourceAcc = createTestAccount(actorId, "CUSTOMER");
        UUID destActorId = createTestUser();
        UUID destAcc = createTestAccount(destActorId, "CUSTOMER");
        UUID journalId = createTestPostedJournal(sourceAcc, destAcc, 10000);
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'USD', ?, ?)",
                UUID.randomUUID(), actorId, sourceAcc, destAcc, journalId, now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Self transfer (source == destination) is rejected by check constraint")
    void selfTransferRejected() {
        UUID actorId = createTestUser();
        UUID sourceAcc = createTestAccount(actorId, "CUSTOMER");
        UUID destActorId = createTestUser();
        UUID destAcc = createTestAccount(destActorId, "CUSTOMER");
        UUID journalId = createTestPostedJournal(sourceAcc, destAcc, 10000);
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', ?, ?)",
                UUID.randomUUID(), actorId, sourceAcc, sourceAcc, journalId, now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Duplicate journal_transaction_id is rejected by unique constraint")
    void duplicateJournalTransactionRejected() {
        UUID actorId = createTestUser();
        UUID sourceAcc = createTestAccount(actorId, "CUSTOMER");
        UUID destActorId = createTestUser();
        UUID destAcc = createTestAccount(destActorId, "CUSTOMER");
        UUID journalId = createTestPostedJournal(sourceAcc, destAcc, 10000);
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', ?, ?)",
                UUID.randomUUID(), actorId, sourceAcc, destAcc, journalId, now
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO transfers (id, initiated_by_user_id, source_ledger_account_id, destination_ledger_account_id, amount_minor, currency, journal_transaction_id, created_at) " +
                        "VALUES (?, ?, ?, ?, 5000, 'INR', ?, ?)",
                UUID.randomUUID(), actorId, sourceAcc, destAcc, journalId, now
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createTestUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'CUSTOMER', 'ACTIVE', ?, ?)",
                id, "trf_test." + id + "@example.com", "$2a$10$dummyHashValueForTestingPurposeOnly", now, now
        );
        return id;
    }

    private UUID createTestAccount(UUID ownerUserId, String accountType) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'INR', 'ACTIVE', ?, ?)",
                id, ownerUserId, accountType, now, now
        );
        return id;
    }

    private UUID createTestDraftJournal() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                id, now
        );
        return id;
    }

    private UUID createTestPostedJournal(UUID sourceAcc, UUID destAcc, long amount) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                id, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', ?)",
                UUID.randomUUID(), id, sourceAcc, amount
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', ?)",
                UUID.randomUUID(), id, destAcc, amount
        );
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, id
        );
        return id;
    }
}
