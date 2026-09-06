package com.ledgerguard.lab.scenarios;

import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.AbstractFailureLabIntegrationTest;
import com.ledgerguard.lab.adapter.HttpProviderTestAdapter;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.payout.application.CreatePayoutCommand;
import com.ledgerguard.payout.application.PayoutResult;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Scenario 2 — Timeout After Commit (Real Payout & Poller Lifecycle)")
class TimeoutAfterCommitScenarioTest extends AbstractFailureLabIntegrationTest {

    @BeforeEach
    void setUp() {
        HTTP_PROVIDER_ADAPTER.clear();
        // Clear any old due poller records
        jdbcTemplate.update("UPDATE payouts SET next_provider_poll_at = CURRENT_TIMESTAMP + INTERVAL '100 days' WHERE next_provider_poll_at IS NOT NULL");
        jdbcTemplate.update("UPDATE funding_operations SET next_provider_poll_at = CURRENT_TIMESTAMP + INTERVAL '100 days' WHERE next_provider_poll_at IS NOT NULL");
    }

    @Test
    @DisplayName("TIMEOUT_AFTER_COMMIT: Payout encounters transport timeout after provider commit, enters UNKNOWN with ACTIVE hold, poller recovers to SUCCEEDED with exactly 1 journal")
    void testTimeoutAfterCommitRealProductionPath() throws Exception {
        // 1. Setup customer user and funded account
        UUID userId = UUID.randomUUID();
        userRepository.save(new User(userId, "payoutUser." + userId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        LedgerAccount customerAccount = ledgerAccountRepository.save(LedgerAccount.createCustomerAccount(userId));
        LedgerAccount clearingAccount = ledgerAccountRepository.findByAccountType(AccountType.PSP_CLEARING)
                .orElseGet(() -> ledgerAccountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));

        // Initial wallet funding: 50,000 minor units (INR 500.00)
        long initialFunds = 50000L;
        long payoutAmount = 10000L; // INR 100.00

        ledgerPostingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(clearingAccount.getId(), EntryDirection.DEBIT, Money.inr(initialFunds)),
                        new PostingLine(customerAccount.getId(), EntryDirection.CREDIT, Money.inr(initialFunds))
                )
        ));

        // 2. Set provider adapter to TIMEOUT_AFTER_SUCCESS
        // External provider commits operation internally, but network drops before response headers
        HTTP_PROVIDER_ADAPTER.setMode(HttpProviderTestAdapter.Mode.TIMEOUT_AFTER_SUCCESS);

        // Explicitly verify production PspClient bean is active with zero test replacement/mock beans
        assertThat(org.springframework.aop.support.AopUtils.getTargetClass(pspClient))
                .isEqualTo(com.ledgerguard.funding.infrastructure.PspClient.class);
        Object ultimateTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(pspClient);
        assertThat(ultimateTarget).isExactlyInstanceOf(com.ledgerguard.funding.infrastructure.PspClient.class);

        // 3. Initiate Payout through REAL LedgerGuard PayoutService
        CreatePayoutCommand cmd = new CreatePayoutCommand(userId, "idem-payout-" + UUID.randomUUID(), Money.inr(payoutAmount));
        PayoutResult result = payoutService.requestPayout(cmd);

        // 4. Assert UNKNOWN Ambiguity Window Invariants
        assertThat(result.status()).isEqualTo(PayoutStatus.UNKNOWN);

        Payout inFlightPayout = payoutRepository.findById(result.payoutId()).orElseThrow();
        assertThat(inFlightPayout.getStatus()).isEqualTo(PayoutStatus.UNKNOWN);
        assertThat(inFlightPayout.getUnknownSince()).isNotNull();
        assertThat(inFlightPayout.getJournalTransactionId()).isNull(); // Zero settlement journals posted during UNKNOWN

        BalanceHold inFlightHold = balanceHoldRepository.findById(inFlightPayout.getBalanceHoldId()).orElseThrow();
        assertThat(inFlightHold.getStatus()).isEqualTo(HoldStatus.ACTIVE); // Hold strictly preserved, preventing double-spend!

        // Confirm external provider committed exactly 1 logical operation despite physical retries
        assertThat(HTTP_PROVIDER_ADAPTER.getOperationCount()).isEqualTo(1);
        int physicalCreateAttempts = HTTP_PROVIDER_ADAPTER.getPhysicalCreateAttempts();
        assertThat(physicalCreateAttempts).isGreaterThanOrEqualTo(1);

        // Oracle check during UNKNOWN: zero settlement journals posted
        try (Connection conn = dataSource.getConnection()) {
            InvariantCheckResult holdCheck = oracle.checkAvailableBalance(conn, customerAccount.getId());
            assertThat(holdCheck.passed()).isTrue();
            InvariantCheckResult journalZeroCheck = oracle.checkSettlementJournalCount(conn, inFlightPayout.getJournalTransactionId(), 0);
            assertThat(journalZeroCheck.passed()).isTrue();
        }

        // 5. Recovery Phase: Make payout immediately due and trigger real background poller
        jdbcTemplate.update(
                "UPDATE payouts SET next_provider_poll_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                inFlightPayout.getId()
        );

        // Run REAL production poller
        pollingService.pollPendingOperations();

        // Confirm recovery queried provider via real PspClient GET
        assertThat(HTTP_PROVIDER_ADAPTER.getPhysicalGetAttempts()).isGreaterThanOrEqualTo(1);

        // 6. Assert Post-Recovery Authoritative Settlement Invariants
        Payout settledPayout = payoutRepository.findById(inFlightPayout.getId()).orElseThrow();
        assertThat(settledPayout.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);
        assertThat(settledPayout.getProviderOperationId()).isNotNull();
        assertThat(settledPayout.getJournalTransactionId()).isNotNull();
        assertThat(settledPayout.getNextProviderPollAt()).isNull();

        BalanceHold settledHold = balanceHoldRepository.findById(settledPayout.getBalanceHoldId()).orElseThrow();
        assertThat(settledHold.getStatus()).isEqualTo(HoldStatus.CONSUMED); // Hold consumed upon authoritative settlement!

        // 7. Independent Oracle Verification: Exactly 1 settlement journal, balanced, snapshot parity
        try (Connection conn = dataSource.getConnection()) {
            List<InvariantCheckResult> checks = new ArrayList<>();

            // A. Structural validity
            checks.addAll(oracle.checkJournalStructure(conn));

            // B. Debit total == Credit total
            checks.addAll(oracle.checkDebitCreditEquality(conn));

            // C. Balance snapshots reconstruct accurately from immutable POSTED journals
            checks.addAll(oracle.checkSnapshotIntegrity(conn, customerAccount.getId()));

            // D. Available balance valid
            checks.add(oracle.checkAvailableBalance(conn, customerAccount.getId()));

            // E. Exactly 1 settlement journal transaction exists for this payout
            checks.add(oracle.checkSettlementJournalCount(conn, settledPayout.getJournalTransactionId(), 1));

            assertThat(checks).allMatch(InvariantCheckResult::passed);
        }
    }
}
