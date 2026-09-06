package com.ledgerguard.lab.scenarios;

import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.adapter.HttpProviderTestAdapter;
import com.ledgerguard.lab.engine.ChaosScenario;
import com.ledgerguard.lab.invariants.FinancialInvariantOracle;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;
import com.ledgerguard.lab.model.ScenarioStepEvent;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.payout.application.CreatePayoutCommand;
import com.ledgerguard.payout.application.PayoutResult;
import com.ledgerguard.payout.application.PayoutService;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.application.ProviderStatusPollingService;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real production scenario executing Payout TIMEOUT_AFTER_SUCCESS
 * and recovery via ProviderStatusPollingService.
 */
public class RealTimeoutAfterCommitScenario implements ChaosScenario {

    private final PayoutService payoutService;
    private final ProviderStatusPollingService pollingService;
    private final PayoutRepository payoutRepository;
    private final BalanceHoldRepository balanceHoldRepository;
    private final UserRepository userRepository;
    private final LedgerAccountRepository accountRepository;
    private final LedgerPostingService postingService;
    private final JdbcTemplate jdbcTemplate;
    private final HttpProviderTestAdapter providerAdapter;
    private final FinancialInvariantOracle oracle = new FinancialInvariantOracle();

    public RealTimeoutAfterCommitScenario(
            PayoutService payoutService,
            ProviderStatusPollingService pollingService,
            PayoutRepository payoutRepository,
            BalanceHoldRepository balanceHoldRepository,
            UserRepository userRepository,
            LedgerAccountRepository accountRepository,
            LedgerPostingService postingService,
            JdbcTemplate jdbcTemplate,
            HttpProviderTestAdapter providerAdapter
    ) {
        this.payoutService = payoutService;
        this.pollingService = pollingService;
        this.payoutRepository = payoutRepository;
        this.balanceHoldRepository = balanceHoldRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.postingService = postingService;
        this.jdbcTemplate = jdbcTemplate;
        this.providerAdapter = providerAdapter;
    }

    @Override
    public ScenarioId getId() {
        return ScenarioId.TIMEOUT_AFTER_COMMIT;
    }

    @Override
    public String getDescription() {
        return "Payout timeout after provider commit (UNKNOWN != FAILED) and recovery settlement via poller";
    }

    @Override
    public ScenarioRunResult execute(UUID runId, DataSource dataSource) throws Exception {
        Instant startTime = Instant.now();
        List<ScenarioStepEvent> timeline = new ArrayList<>();
        List<InvariantCheckResult> invariantResults = new ArrayList<>();

        timeline.add(ScenarioStepEvent.of("SETUP", "Seeding user, customer account, and initial wallet funding"));

        UUID userId = UUID.randomUUID();
        userRepository.save(new User(userId, "payout." + userId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        LedgerAccount customerAccount = accountRepository.save(LedgerAccount.createCustomerAccount(userId));
        LedgerAccount clearingAccount = accountRepository.findByAccountType(AccountType.PSP_CLEARING)
                .orElseGet(() -> accountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));

        long initialFunds = 50000L;
        long payoutAmount = 10000L;

        postingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(clearingAccount.getId(), EntryDirection.DEBIT, Money.inr(initialFunds)),
                        new PostingLine(customerAccount.getId(), EntryDirection.CREDIT, Money.inr(initialFunds))
                )
        ));

        timeline.add(ScenarioStepEvent.of("INJECT_FAULT", "Configuring provider adapter for TIMEOUT_AFTER_SUCCESS"));
        providerAdapter.setMode(HttpProviderTestAdapter.Mode.TIMEOUT_AFTER_SUCCESS);

        timeline.add(ScenarioStepEvent.of("DISPATCH_PAYOUT", "Requesting payout via production PayoutService"));
        CreatePayoutCommand cmd = new CreatePayoutCommand(userId, "idem-payout-" + UUID.randomUUID(), Money.inr(payoutAmount));
        PayoutResult result = payoutService.requestPayout(cmd);

        if (result.status() != PayoutStatus.UNKNOWN) {
            throw new IllegalStateException("Expected UNKNOWN status but got: " + result.status());
        }

        Payout inFlightPayout = payoutRepository.findById(result.payoutId()).orElseThrow();
        BalanceHold inFlightHold = balanceHoldRepository.findById(inFlightPayout.getBalanceHoldId()).orElseThrow();

        if (inFlightHold.getStatus() != HoldStatus.ACTIVE) {
            throw new IllegalStateException("Expected ACTIVE hold during UNKNOWN but found: " + inFlightHold.getStatus());
        }
        timeline.add(ScenarioStepEvent.of("AMBIGUITY_CONFIRMED", "Payout marked UNKNOWN, hold preserved ACTIVE, 0 journals"));

        // Recovery phase
        timeline.add(ScenarioStepEvent.of("TRIGGER_RECOVERY", "Advancing poll schedule and triggering ProviderStatusPollingService"));
        jdbcTemplate.update(
                "UPDATE payouts SET next_provider_poll_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                inFlightPayout.getId()
        );

        pollingService.pollPendingOperations();

        Payout settledPayout = payoutRepository.findById(inFlightPayout.getId()).orElseThrow();
        if (settledPayout.getStatus() != PayoutStatus.SUCCEEDED) {
            throw new IllegalStateException("Expected SUCCEEDED payout after recovery but got: " + settledPayout.getStatus());
        }

        BalanceHold settledHold = balanceHoldRepository.findById(settledPayout.getBalanceHoldId()).orElseThrow();
        if (settledHold.getStatus() != HoldStatus.CONSUMED) {
            throw new IllegalStateException("Expected CONSUMED hold after recovery but got: " + settledHold.getStatus());
        }
        timeline.add(ScenarioStepEvent.of("SETTLED", "Payout settled to SUCCEEDED and hold CONSUMED"));

        timeline.add(ScenarioStepEvent.of("ORACLE_AUDIT", "Running independent oracle verification"));
        try (Connection conn = dataSource.getConnection()) {
            invariantResults.addAll(oracle.checkJournalStructure(conn));
            invariantResults.addAll(oracle.checkDebitCreditEquality(conn));
            invariantResults.addAll(oracle.checkSnapshotIntegrity(conn, customerAccount.getId()));
            invariantResults.add(oracle.checkAvailableBalance(conn, customerAccount.getId()));
            invariantResults.add(oracle.checkSettlementJournalCount(conn, settledPayout.getJournalTransactionId(), 1));
        }

        boolean allPassed = invariantResults.stream().allMatch(InvariantCheckResult::passed);
        Instant endTime = Instant.now();

        return new ScenarioRunResult(
                runId, getId(), allPassed ? ScenarioStatus.PASSED : ScenarioStatus.FAILED,
                startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                invariantResults, timeline, allPassed ? null : "Invariant check failed"
        );
    }
}
