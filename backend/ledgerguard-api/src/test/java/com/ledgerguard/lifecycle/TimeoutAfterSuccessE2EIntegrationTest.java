package com.ledgerguard.lifecycle;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.funding.infrastructure.PspTransportException;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.payout.application.CreatePayoutCommand;
import com.ledgerguard.payout.application.PayoutResult;
import com.ledgerguard.payout.application.PayoutService;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.application.ProviderStatusPollingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "ledgerguard.psp.polling.enabled=true")
class TimeoutAfterSuccessE2EIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private ProviderStatusPollingService pollingService;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private BalanceHoldRepository balanceHoldRepository;

    @Autowired
    private PspClient mockPspClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID customerAccountId;
    private UUID pspClearingAccountId;

    @TestConfiguration
    static class MockPspConfig {
        @Bean
        @Primary
        public PspClient pspClient() {
            return mock(PspClient.class);
        }
    }

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(mockPspClient);

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

        // Fund customer account
        UUID journalTxnId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO journal_transactions (id, status, currency, created_at, posted_at) " +
                        "VALUES (?, 'DRAFT', 'INR', ?, NULL)",
                journalTxnId, now
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'DEBIT', 500000)",
                UUID.randomUUID(), journalTxnId, pspClearingAccountId
        );
        jdbcTemplate.update(
                "INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) " +
                        "VALUES (?, ?, ?, 'CREDIT', 500000)",
                UUID.randomUUID(), journalTxnId, customerAccountId
        );
        jdbcTemplate.update(
                "UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalTxnId
        );
    }

    @Test
    @DisplayName("TIMEOUT_AFTER_SUCCESS: Payout encounters timeout, enters UNKNOWN with ACTIVE hold, poller queries provider and settles SUCCEEDED")
    void timeoutAfterSuccessSettledByPoller() {
        UUID providerOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // 1. First outbound POST throws PspTransportException (network timeout)
        when(mockPspClient.createOperation(any(), any(), any(), any()))
                .thenThrow(new PspTransportException("Connection timed out after write", null));

        CreatePayoutCommand cmd = new CreatePayoutCommand(userId, "key-timeout-test", Money.inr(10000));
        PayoutResult result = payoutService.requestPayout(cmd);

        // 2. Local Payout must be in UNKNOWN status, hold must remain ACTIVE, 0 journal posted
        assertThat(result.status()).isEqualTo(PayoutStatus.UNKNOWN);

        Payout payout = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.UNKNOWN);
        assertThat(payout.getUnknownSince()).isNotNull();
        assertThat(payout.getJournalTransactionId()).isNull();

        BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        // 3. Make the mock provider return SUCCEEDED when poller queries GET
        PspOperationResponse providerSuccessResp = new PspOperationResponse(
                providerOpId,
                payout.getId(),
                "DEBIT",
                "10000",
                "INR",
                "SUCCEEDED",
                now.toString(),
                now.toString(),
                false
        );
        when(mockPspClient.getOperationByClientOperationId(payout.getId()))
                .thenReturn(Optional.of(providerSuccessResp));

        // Advance next_provider_poll_at to make it immediately due for polling
        jdbcTemplate.update(
                "UPDATE payouts SET next_provider_poll_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                payout.getId()
        );

        // 4. Run the background poller
        pollingService.pollPendingOperations();

        // 5. Verify Payout is settled SUCCEEDED, hold is CONSUMED, journal transaction is POSTED
        Payout settledPayout = payoutRepository.findById(payout.getId()).orElseThrow();
        assertThat(settledPayout.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(settledPayout.getProviderOperationId()).isEqualTo(providerOpId);
        assertThat(settledPayout.getJournalTransactionId()).isNotNull();
        assertThat(settledPayout.getNextProviderPollAt()).isNull();

        BalanceHold settledHold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(settledHold.getStatus()).isEqualTo(HoldStatus.CONSUMED);

        // 6. Exactly ONE journal transaction (POSTED) and exactly TWO balanced entries
        UUID jtxnId = settledPayout.getJournalTransactionId();
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

        // 7. No duplicate journal transactions exist for this payout
        Integer jtxnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT jo.id) FROM journal_transactions jo " +
                "JOIN payouts p ON p.journal_transaction_id = jo.id WHERE p.id = ?",
                Integer.class, payout.getId());
        assertThat(jtxnCount).isEqualTo(1);
    }
}
