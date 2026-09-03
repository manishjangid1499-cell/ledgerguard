package com.ledgerguard.lifecycle;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.application.FundingPollingHelper;
import com.ledgerguard.funding.application.FundingSettlementService;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FinalAttemptExhaustionRaceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private FundingPollingHelper fundingPollingHelper;

    @Autowired
    private FundingSettlementService fundingSettlementService;

    @Autowired
    private FundingOperationRepository fundingOperationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID customerAccountId;
    private UUID pspClearingAccountId;

    @BeforeEach
    void setUp() {
        // Push any existing rows into the far future so poller ignores them
        jdbcTemplate.update("UPDATE payouts SET next_provider_poll_at = CURRENT_TIMESTAMP + INTERVAL '100 days' WHERE next_provider_poll_at IS NOT NULL");
        jdbcTemplate.update("UPDATE funding_operations SET next_provider_poll_at = CURRENT_TIMESTAMP + INTERVAL '100 days' WHERE next_provider_poll_at IS NOT NULL");

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
    }

    @Test
    @DisplayName("Step 0 finalizer moves exhausted row to RECONCILIATION_REQUIRED, and slow late in-flight GET SUCCEEDED cleanly resolves it to SUCCEEDED")
    void lateGetSucceededResolvesReconciliationRequired() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // 1. Funding row in PROCESSING with provider_poll_attempts = 10 (max) and next_provider_poll_at <= now
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );
        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', provider_poll_attempts = 10, next_provider_poll_at = ? WHERE id = ?",
                now, fundingId
        );

        // 2. Step 0 Exhaustion Finalizer claims it and moves to RECONCILIATION_REQUIRED
        int finalized = fundingPollingHelper.finalizeExhausted(Instant.now(), 10, 10);
        assertThat(finalized).isEqualTo(1);

        FundingOperation afterFinalizer = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(afterFinalizer.getStatus()).isEqualTo(FundingStatus.RECONCILIATION_REQUIRED);
        assertThat(afterFinalizer.getNextProviderPollAt()).isNull();

        // 3. The in-flight GET (which was running concurrently across HTTP) now completes with authoritative SUCCEEDED
        PspOperationResponse lateSuccessResp = new PspOperationResponse(
                providerOpId, fundingId, "CREDIT", "10000", "INR", "SUCCEEDED", now.toString(), now.toString(), false);

        FundingOperation settled = fundingSettlementService.settleFunding(fundingId, lateSuccessResp);

        // 4. Operation successfully resolved to SUCCEEDED with journal posted
        assertThat(settled.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(settled.getProviderOperationId()).isEqualTo(providerOpId);
        assertThat(settled.getJournalTransactionId()).isNotNull();

        FundingOperation reloaded = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(reloaded.getNextProviderPollAt()).isNull();
    }
}
