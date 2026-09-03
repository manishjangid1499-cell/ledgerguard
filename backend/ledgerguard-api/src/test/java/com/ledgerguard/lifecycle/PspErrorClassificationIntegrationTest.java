package com.ledgerguard.lifecycle;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspProtocolException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

class PspErrorClassificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private BalanceHoldRepository balanceHoldRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PspClient mockPspClient;

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
        reset(mockPspClient);
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
    @DisplayName("temporaryFailureProblemTypeIsDefiniteFailure: 500 with temporary-failure URN results in local FAILED, null provider ID, RELEASED hold")
    void temporaryFailureProblemTypeIsDefiniteFailure() {
        when(mockPspClient.createOperation(any(), any(), any(), any()))
                .thenThrow(new PspProtocolException("Simulated 500", 500, "urn:ledgerguard:psp:error:temporary-failure"));

        CreatePayoutCommand cmd = new CreatePayoutCommand(userId, "key-temp-500", Money.inr(10000));
        PayoutResult result = payoutService.requestPayout(cmd);

        assertThat(result.status()).isEqualTo(PayoutStatus.FAILED);
        assertThat(result.providerOperationId()).isNull();

        Payout payout = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(payout.getProviderOperationId()).isNull();
        assertThat(payout.getJournalTransactionId()).isNull();

        BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    @DisplayName("generic500IsUnknown: 500 without recognized problem type enters UNKNOWN with ACTIVE hold")
    void generic500IsUnknown() {
        when(mockPspClient.createOperation(any(), any(), any(), any()))
                .thenThrow(new PspProtocolException("Generic 500", 500, null));

        CreatePayoutCommand cmd = new CreatePayoutCommand(userId, "key-generic-500", Money.inr(10000));
        PayoutResult result = payoutService.requestPayout(cmd);

        assertThat(result.status()).isEqualTo(PayoutStatus.UNKNOWN);

        Payout payout = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.UNKNOWN);
        assertThat(payout.getUnknownSince()).isNotNull();

        BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    @DisplayName("client400BadRequestIsDefiniteFailure: 400 client error results in local FAILED and RELEASED hold")
    void client400BadRequestIsDefiniteFailure() {
        when(mockPspClient.createOperation(any(), any(), any(), any()))
                .thenThrow(new PspProtocolException("Bad Request", 400, "urn:ledgerguard:psp:error:invalid-request"));

        CreatePayoutCommand cmd = new CreatePayoutCommand(userId, "key-400-test", Money.inr(10000));
        PayoutResult result = payoutService.requestPayout(cmd);

        assertThat(result.status()).isEqualTo(PayoutStatus.FAILED);

        Payout payout = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);

        BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    @DisplayName("unknown500ProblemTypeIsUnknown: 500 with unknown error URN enters UNKNOWN")
    void unknown500ProblemTypeIsUnknown() {
        when(mockPspClient.createOperation(any(), any(), any(), any()))
                .thenThrow(new PspProtocolException("Unknown 500", 500, "urn:other:error:internal"));

        CreatePayoutCommand cmd = new CreatePayoutCommand(userId, "key-unknown-500", Money.inr(10000));
        PayoutResult result = payoutService.requestPayout(cmd);

        assertThat(result.status()).isEqualTo(PayoutStatus.UNKNOWN);
    }

    @Test
    @DisplayName("conflictingReplayProblemTypeIsReconciliationRequired: 409 conflicting-replay enters RECONCILIATION_REQUIRED")
    void conflictingReplayProblemTypeIsReconciliationRequired() {
        when(mockPspClient.createOperation(any(), any(), any(), any()))
                .thenThrow(new PspProtocolException("Conflict", 409, "urn:ledgerguard:psp:error:conflicting-replay"));

        CreatePayoutCommand cmd = new CreatePayoutCommand(userId, "key-409-conflict", Money.inr(10000));
        PayoutResult result = payoutService.requestPayout(cmd);

        assertThat(result.status()).isEqualTo(PayoutStatus.RECONCILIATION_REQUIRED);

        Payout payout = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.RECONCILIATION_REQUIRED);
        assertThat(payout.getNextProviderPollAt()).isNull();

        BalanceHold hold = balanceHoldRepository.findById(payout.getBalanceHoldId()).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }
}
