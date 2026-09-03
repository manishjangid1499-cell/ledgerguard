package com.ledgerguard.lifecycle;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalStateMachineDatabaseConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID customerAccountId;
    private UUID pspClearingAccountId;

    @BeforeEach
    void setUp() {
        Timestamp now = Timestamp.from(Instant.now());
        userId = UUID.randomUUID();
        customerAccountId = UUID.randomUUID();
        pspClearingAccountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                userId, "cust-" + userId + "@example.com", now, now
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                customerAccountId, userId, now, now
        );

        jdbcTemplate.update(
                "UPDATE ledger_accounts SET status = 'CLOSED' WHERE account_type = 'PSP_CLEARING' AND currency = 'INR'"
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, NULL, 'PSP_CLEARING', 'INR', 'ACTIVE', ?, ?)",
                pspClearingAccountId, now, now
        );

        // Pre-fund customer account so balance holds succeed
        UUID initTxn = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) " +
                        "VALUES (?, 'DRAFT', 'INR', ?, NULL)",
                initTxn, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', 500000)",
                UUID.randomUUID(), initTxn, pspClearingAccountId
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', 500000)",
                UUID.randomUUID(), initTxn, customerAccountId
        );
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, initTxn
        );
    }

    private UUID createHold(String status) {
        UUID holdId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(Duration.ofMinutes(30)));
        jdbcTemplate.update(
                "INSERT INTO balance_holds (id, ledger_account_id, amount_minor, currency, status, expires_at, created_at, updated_at, terminal_at) " +
                        "VALUES (?, ?, 10000, 'INR', 'ACTIVE', ?, ?, ?, NULL)",
                holdId, customerAccountId, expiresAt, now, now
        );
        if (!"ACTIVE".equals(status)) {
            jdbcTemplate.update(
                    "UPDATE balance_holds SET status = ?, terminal_at = ? WHERE id = ?",
                    status, now, holdId
            );
        }
        return holdId;
    }

    @Test
    @DisplayName("V13 direct SQL rejects PROCESSING with null nextPoll")
    void rejectingProcessingWithNullNextPoll() {
        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = NULL WHERE id = ?",
                fundingId
        )).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("V13 direct SQL rejects UNKNOWN with null nextPoll or null unknownSince")
    void rejectingUnknownWithInvalidMetadata() {
        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );

        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        );

        // Null nextPoll in UNKNOWN
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'UNKNOWN', unknown_since = ?, next_provider_poll_at = NULL WHERE id = ?",
                now, fundingId
        )).isInstanceOf(Exception.class);

        // Null unknownSince in UNKNOWN
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'UNKNOWN', unknown_since = NULL, next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        )).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("V13 direct SQL rejects RECONCILIATION_REQUIRED or terminal states with non-null nextPoll")
    void rejectingTerminalOrReconciliationWithNonNullNextPoll() {
        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );

        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        );

        // RECONCILIATION_REQUIRED with non-null nextPoll
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'RECONCILIATION_REQUIRED', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        )).isInstanceOf(Exception.class);

        // FAILED with non-null nextPoll
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'FAILED', completed_at = ?, next_provider_poll_at = ? WHERE id = ?",
                now, now, fundingId
        )).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("V13 allows same-status recovery metadata updates for PROCESSING and UNKNOWN")
    void sameStatusRecoveryMetadataUpdatesAllowed() {
        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );

        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        );

        // PROCESSING -> PROCESSING incrementing attempts and advancing nextPoll
        Timestamp nextPoll = Timestamp.from(Instant.now().plusSeconds(10));
        int updated = jdbcTemplate.update(
                "UPDATE funding_operations SET provider_poll_attempts = provider_poll_attempts + 1, next_provider_poll_at = ? WHERE id = ?",
                nextPoll, fundingId
        );
        assertThat(updated).isEqualTo(1);

        // Attempting to modify immutable business fields in same-status update is rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET amount_minor = 20000 WHERE id = ?",
                fundingId
        )).isInstanceOf(Exception.class).hasMessageContaining("Immutable business fields");
    }

    @Test
    @DisplayName("Transition-origin provider ID rules: CREATED->FAILED requires null ID; provider-attempted->FAILED requires non-null ID")
    void transitionOriginProviderIdRules() {
        UUID fundingId1 = UUID.randomUUID();
        UUID fundingId2 = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        UUID providerOpId = UUID.randomUUID();

        // funding1: CREATED -> FAILED with non-null provider ID is rejected
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId1, userId, customerAccountId, now
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'FAILED', provider_operation_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                providerOpId, now, fundingId1
        )).isInstanceOf(Exception.class).hasMessageContaining("failing from CREATED must have provider_operation_id NULL");

        // CREATED -> FAILED with NULL provider ID succeeds
        int rows = jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'FAILED', provider_operation_id = NULL, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                now, fundingId1
        );
        assertThat(rows).isEqualTo(1);

        // funding2: CREATED -> PROCESSING -> UNKNOWN -> FAILED with NULL provider ID is rejected
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId2, userId, customerAccountId, now
        );
        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId2
        );
        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'UNKNOWN', unknown_since = ?, next_provider_poll_at = ? WHERE id = ?",
                now, now, fundingId2
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'FAILED', provider_operation_id = NULL, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                now, fundingId2
        )).isInstanceOf(Exception.class).hasMessageContaining("failing from provider-attempted state UNKNOWN must have provider_operation_id populated");

        // UNKNOWN -> FAILED with non-null provider ID succeeds
        int rows2 = jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'FAILED', provider_operation_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                providerOpId, now, fundingId2
        );
        assertThat(rows2).isEqualTo(1);
    }

    @Test
    @DisplayName("Transition-origin payout hold rules: CREATED->FAILED accepts EXPIRED hold; provider-attempted requires RELEASED")
    void transitionOriginPayoutHoldRules() {
        UUID payoutId1 = UUID.randomUUID();
        UUID payoutId2 = UUID.randomUUID();
        UUID holdId1 = createHold("ACTIVE");
        UUID activeHoldId = createHold("ACTIVE");
        Timestamp now = Timestamp.from(Instant.now());
        UUID providerOpId = UUID.randomUUID();

        // Payout 1: CREATED with expired hold transitioning to FAILED succeeds
        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'CREATED', ?)",
                payoutId1, userId, customerAccountId, holdId1, now
        );

        jdbcTemplate.update(
                "UPDATE balance_holds SET status = 'EXPIRED', terminal_at = ? WHERE id = ?",
                now, holdId1
        );

        int rows = jdbcTemplate.update(
                "UPDATE payouts SET status = 'FAILED', completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                now, payoutId1
        );
        assertThat(rows).isEqualTo(1);

        // Payout 2: in UNKNOWN with EXPIRED hold transitioning to FAILED is rejected (must be RELEASED)
        jdbcTemplate.update(
                "INSERT INTO payouts (id, initiated_by_user_id, source_ledger_account_id, balance_hold_id, amount_minor, currency, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 10000, 'INR', 'CREATED', ?)",
                payoutId2, userId, customerAccountId, activeHoldId, now
        );
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, payoutId2
        );
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'UNKNOWN', unknown_since = ?, next_provider_poll_at = ? WHERE id = ?",
                now, now, payoutId2
        );

        // Payout 2: in UNKNOWN with ACTIVE hold transitioning to FAILED is rejected (must be RELEASED first)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payouts SET status = 'FAILED', provider_operation_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                providerOpId, now, payoutId2
        )).isInstanceOf(Exception.class).hasMessageContaining("must be RELEASED");

        // Release the hold and then finalize payout to FAILED
        jdbcTemplate.update("UPDATE balance_holds SET status = 'RELEASED', terminal_at = ? WHERE id = ?", now, activeHoldId);
        jdbcTemplate.update(
                "UPDATE payouts SET status = 'FAILED', provider_operation_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                providerOpId, now, payoutId2
        );
    }

    @Test
    @DisplayName("One-way providerOperationId binding rejects conflicting change")
    void oneWayProviderIdBinding() {
        UUID fundingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        UUID providerOpId1 = UUID.randomUUID();
        UUID providerOpId2 = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );

        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        );

        // Bind providerOpId1
        jdbcTemplate.update(
                "UPDATE funding_operations SET provider_operation_id = ? WHERE id = ?",
                providerOpId1, fundingId
        );

        // Rebinding same ID is allowed
        int updated = jdbcTemplate.update(
                "UPDATE funding_operations SET provider_operation_id = ? WHERE id = ?",
                providerOpId1, fundingId
        );
        assertThat(updated).isEqualTo(1);

        // Binding different ID is rejected
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE funding_operations SET provider_operation_id = ? WHERE id = ?",
                providerOpId2, fundingId
        )).isInstanceOf(Exception.class).hasMessageContaining("Cannot modify provider_operation_id");
    }
}
