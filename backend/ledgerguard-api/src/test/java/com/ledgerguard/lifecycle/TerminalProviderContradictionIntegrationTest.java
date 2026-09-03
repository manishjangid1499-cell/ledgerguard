package com.ledgerguard.lifecycle;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.application.FundingFailureService;
import com.ledgerguard.funding.application.FundingSettlementService;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.payout.application.PayoutFailureService;
import com.ledgerguard.payout.application.PayoutSettlementService;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.application.ProviderEventConflictException;
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

class TerminalProviderContradictionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private FundingSettlementService fundingSettlementService;

    @Autowired
    private FundingFailureService fundingFailureService;

    @Autowired
    private PayoutSettlementService payoutSettlementService;

    @Autowired
    private PayoutFailureService payoutFailureService;

    @Autowired
    private FundingOperationRepository fundingOperationRepository;

    @Autowired
    private PayoutRepository payoutRepository;

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
                userId, "user-" + userId + "@example.com", now, now
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

        // Pre-fund customer account for payouts
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
    @DisplayName("FAILED + same FAILED is idempotent; FAILED + different provider ID throws conflict")
    void failedReplayAndConflict() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId1 = UUID.randomUUID();
        UUID providerOpId2 = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Setup funding in FAILED state with providerOpId1
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );
        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        );
        fundingFailureService.failFunding(fundingId, providerOpId1, Instant.now());

        // 1. Same FAILED with matching providerOpId1 -> idempotent return
        FundingOperation sameResult = fundingFailureService.failFunding(fundingId, providerOpId1, Instant.now());
        assertThat(sameResult.getStatus()).isEqualTo(FundingStatus.FAILED);
        assertThat(sameResult.getProviderOperationId()).isEqualTo(providerOpId1);

        // 2. FAILED with different providerOpId2 -> throws conflict
        assertThatThrownBy(() -> fundingFailureService.failFunding(fundingId, providerOpId2, Instant.now()))
                .isInstanceOf(ProviderEventConflictException.class);
    }

    @Test
    @DisplayName("SUCCEEDED + incoming FAILED throws conflict; local remains SUCCEEDED without financial mutation")
    void succeededPlusFailedThrowsConflict() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Setup funding in PROCESSING
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );
        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        );

        PspOperationResponse successResp = new PspOperationResponse(
                providerOpId, fundingId, "CREDIT", "10000", "INR", "SUCCEEDED", now.toString(), now.toString(), false);

        fundingSettlementService.settleFunding(fundingId, successResp);

        // Attempting to fail a SUCCEEDED operation throws conflict
        assertThatThrownBy(() -> fundingFailureService.failFunding(fundingId, providerOpId, Instant.now()))
                .isInstanceOf(ProviderEventConflictException.class);

        FundingOperation funding = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(funding.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(funding.getJournalTransactionId()).isNotNull();

        // Exactly ONE journal transaction (POSTED), exactly TWO balanced entries (1 DEBIT + 1 CREDIT)
        UUID jtxnId = funding.getJournalTransactionId();
        String jtxnStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM journal_transactions WHERE id = ?", String.class, jtxnId);
        assertThat(jtxnStatus).isEqualTo("POSTED");

        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE journal_transaction_id = ?", Integer.class, jtxnId);
        assertThat(entryCount).isEqualTo(2);

        Integer debitCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE journal_transaction_id = ? AND direction = 'DEBIT'", Integer.class, jtxnId);
        Integer creditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE journal_transaction_id = ? AND direction = 'CREDIT'", Integer.class, jtxnId);
        assertThat(debitCount).isEqualTo(1);
        assertThat(creditCount).isEqualTo(1);

        Long debitAmount = jdbcTemplate.queryForObject(
                "SELECT amount_minor FROM journal_entries WHERE journal_transaction_id = ? AND direction = 'DEBIT'", Long.class, jtxnId);
        Long creditAmount = jdbcTemplate.queryForObject(
                "SELECT amount_minor FROM journal_entries WHERE journal_transaction_id = ? AND direction = 'CREDIT'", Long.class, jtxnId);
        assertThat(debitAmount).isEqualTo(creditAmount);
        assertThat(debitAmount).isEqualTo(10000L);

        // No duplicate journal transactions for this operation
        Integer jtxnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT jo.id) FROM journal_transactions jo " +
                "JOIN funding_operations f ON f.journal_transaction_id = jo.id WHERE f.id = ?",
                Integer.class, fundingId);
        assertThat(jtxnCount).isEqualTo(1);
    }

    @Test
    @DisplayName("FAILED + incoming SUCCEEDED throws conflict; 0 financial mutation; remains FAILED")
    void failedPlusSucceededThrowsConflict() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
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
        fundingFailureService.failFunding(fundingId, providerOpId, Instant.now());

        PspOperationResponse successResp = new PspOperationResponse(
                providerOpId, fundingId, "CREDIT", "10000", "INR", "SUCCEEDED", now.toString(), now.toString(), false);

        assertThatThrownBy(() -> fundingSettlementService.settleFunding(fundingId, successResp))
                .isInstanceOf(ProviderEventConflictException.class);

        FundingOperation funding = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(funding.getStatus()).isEqualTo(FundingStatus.FAILED);
        assertThat(funding.getJournalTransactionId()).isNull();
    }
}
