package com.ledgerguard.lab.scenarios;

import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.engine.ChaosScenario;
import com.ledgerguard.lab.engine.ScenarioEventSink;
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
import com.ledgerguard.transfer.application.CreateTransferCommand;
import com.ledgerguard.transfer.application.TransferResult;
import com.ledgerguard.transfer.application.TransferService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Real production scenario executing concurrent opposing transfers
 * via LedgerGuard's TransferService.
 */
public class RealOpposingTransfersScenario implements ChaosScenario {

    private final TransferService transferService;
    private final LedgerPostingService postingService;
    private final UserRepository userRepository;
    private final LedgerAccountRepository accountRepository;
    private final FinancialInvariantOracle oracle = new FinancialInvariantOracle();

    public RealOpposingTransfersScenario(
            TransferService transferService,
            LedgerPostingService postingService,
            UserRepository userRepository,
            LedgerAccountRepository accountRepository
    ) {
        this.transferService = transferService;
        this.postingService = postingService;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    public ScenarioId getId() {
        return ScenarioId.OPPOSING_TRANSFERS;
    }

    @Override
    public String getDescription() {
        return "Concurrent opposing transfers A->B and B->A via production TransferService";
    }

    @Override
    public ScenarioRunResult execute(UUID runId, DataSource dataSource, ScenarioEventSink sink) throws Exception {
        Instant startTime = Instant.now();
        List<ScenarioStepEvent> timeline = new ArrayList<>();
        List<InvariantCheckResult> invariantResults = new ArrayList<>();

        emit(timeline, sink, ScenarioStepEvent.of("SETUP", "Seeding real users, accounts, and initial funded balances"));

        UUID userAId = UUID.randomUUID();
        UUID userBId = UUID.randomUUID();

        userRepository.save(new User(userAId, "userA." + userAId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        userRepository.save(new User(userBId, "userB." + userBId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        LedgerAccount accountA = accountRepository.save(LedgerAccount.createCustomerAccount(userAId));
        LedgerAccount accountB = accountRepository.save(LedgerAccount.createCustomerAccount(userBId));

        LedgerAccount clearing = accountRepository.findByAccountType(AccountType.PSP_CLEARING)
                .orElseGet(() -> accountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));

        long initialEach = 10000L;
        long totalInitialMoney = initialEach * 2;

        postingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(clearing.getId(), EntryDirection.DEBIT, Money.inr(initialEach)),
                        new PostingLine(accountA.getId(), EntryDirection.CREDIT, Money.inr(initialEach))
                )
        ));

        postingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(clearing.getId(), EntryDirection.DEBIT, Money.inr(initialEach)),
                        new PostingLine(accountB.getId(), EntryDirection.CREDIT, Money.inr(initialEach))
                )
        ));

        emit(timeline, sink, ScenarioStepEvent.of("DISPATCH", "Dispatching concurrent A->B and B->A via production TransferService"));

        long transferAmount = 5000L;
        CreateTransferCommand cmdAtoB = new CreateTransferCommand(
                userAId, accountB.getId(), Money.inr(transferAmount), "idem-A-B-" + UUID.randomUUID()
        );
        CreateTransferCommand cmdBtoA = new CreateTransferCommand(
                userBId, accountA.getId(), Money.inr(transferAmount), "idem-B-A-" + UUID.randomUUID()
        );

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<TransferResult> future1 = executor.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            return transferService.createTransfer(cmdAtoB);
        });

        Future<TransferResult> future2 = executor.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            return transferService.createTransfer(cmdBtoA);
        });

        TransferResult res1 = future1.get(10, TimeUnit.SECONDS);
        TransferResult res2 = future2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        emit(timeline, sink, ScenarioStepEvent.of("COMPLETED", "Both transfers completed without deadlock"));

        // Idempotency replay check
        TransferResult replay = transferService.createTransfer(cmdAtoB);
        if (replay.replayed()) {
            emit(timeline, sink, ScenarioStepEvent.of("IDEMPOTENCY_VERIFIED", "Idempotent replay produced zero duplicate transfers"));
        }

        emit(timeline, sink, ScenarioStepEvent.of("ORACLE_AUDIT", "Executing independent financial invariant oracle"));

        try (Connection conn = dataSource.getConnection()) {
            invariantResults.addAll(oracle.checkJournalStructure(conn));
            invariantResults.addAll(oracle.checkDebitCreditEquality(conn));
            invariantResults.addAll(oracle.checkSnapshotIntegrity(conn, accountA.getId(), accountB.getId()));
            invariantResults.add(oracle.checkAvailableBalance(conn, accountA.getId()));
            invariantResults.add(oracle.checkAvailableBalance(conn, accountB.getId()));
            invariantResults.add(oracle.checkInternalTransferConservation(conn, accountA.getId(), accountB.getId(), totalInitialMoney));
        }

        boolean allPassed = invariantResults.stream().allMatch(InvariantCheckResult::passed);
        Instant endTime = Instant.now();

        return new ScenarioRunResult(
                runId, getId(), allPassed ? ScenarioStatus.PASSED : ScenarioStatus.FAILED,
                startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                invariantResults, timeline, allPassed ? null : "Invariant check failed"
        );
    }

    private void emit(List<ScenarioStepEvent> timeline, ScenarioEventSink sink, ScenarioStepEvent event) {
        timeline.add(event);
        if (sink != null) {
            sink.onStep(event);
        }
    }
}
