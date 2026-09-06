package com.ledgerguard.lab.scenarios;

import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.AbstractFailureLabIntegrationTest;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.transfer.application.CreateTransferCommand;
import com.ledgerguard.transfer.application.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Scenario 1 — Opposing Transfers (Real TransferService)")
class OpposingTransfersScenarioTest extends AbstractFailureLabIntegrationTest {

    @Test
    @DisplayName("OPPOSING_TRANSFERS: Concurrent A->B and B->A via real TransferService with deterministic locking and money conservation")
    void testOpposingTransfersRealProductionPath() throws Exception {
        // 1. Setup fixture via real repositories and real posting service
        UUID userAId = UUID.randomUUID();
        UUID userBId = UUID.randomUUID();

        userRepository.save(new User(userAId, "userA." + userAId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        userRepository.save(new User(userBId, "userB." + userBId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        LedgerAccount accountA = ledgerAccountRepository.save(LedgerAccount.createCustomerAccount(userAId));
        LedgerAccount accountB = ledgerAccountRepository.save(LedgerAccount.createCustomerAccount(userBId));

        LedgerAccount clearing = ledgerAccountRepository.findByAccountType(AccountType.PSP_CLEARING)
                .orElseGet(() -> ledgerAccountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));

        // Initial funding: 10,000 minor units (INR 100.00) each via real LedgerPostingService
        long initialEach = 10000L;
        long totalInitialMoney = initialEach * 2;

        ledgerPostingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(clearing.getId(), EntryDirection.DEBIT, Money.inr(initialEach)),
                        new PostingLine(accountA.getId(), EntryDirection.CREDIT, Money.inr(initialEach))
                )
        ));

        ledgerPostingService.post(new PostJournalCommand(
                List.of(
                        new PostingLine(clearing.getId(), EntryDirection.DEBIT, Money.inr(initialEach)),
                        new PostingLine(accountB.getId(), EntryDirection.CREDIT, Money.inr(initialEach))
                )
        ));

        // 2. Prepare concurrent transfer commands (5,000 minor units each)
        long transferAmount = 5000L;
        CreateTransferCommand cmdAtoB = new CreateTransferCommand(
                userAId,
                accountB.getId(),
                Money.inr(transferAmount),
                "idem-opposing-A-to-B-" + UUID.randomUUID()
        );

        CreateTransferCommand cmdBtoA = new CreateTransferCommand(
                userBId,
                accountA.getId(),
                Money.inr(transferAmount),
                "idem-opposing-B-to-A-" + UUID.randomUUID()
        );

        // 3. Dispatch concurrently via CyclicBarrier(2) invoking real TransferService
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<TransferResult> future1 = executor.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            // Real production entry point with @Transactional proxy active
            return transferService.createTransfer(cmdAtoB);
        });

        Future<TransferResult> future2 = executor.submit(() -> {
            barrier.await(5, TimeUnit.SECONDS);
            // Real production entry point with @Transactional proxy active
            return transferService.createTransfer(cmdBtoA);
        });

        TransferResult res1 = future1.get(10, TimeUnit.SECONDS);
        TransferResult res2 = future2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(res1).isNotNull();
        assertThat(res2).isNotNull();
        assertThat(res1.replayed()).isFalse();
        assertThat(res2.replayed()).isFalse();

        // 4. Test idempotency replay on deliberate retry
        TransferResult replayRes1 = transferService.createTransfer(cmdAtoB);
        assertThat(replayRes1.replayed()).isTrue();
        assertThat(replayRes1.transferId()).isEqualTo(res1.transferId());

        // 5. Independent Oracle Invariant Audit
        try (Connection conn = dataSource.getConnection()) {
            List<InvariantCheckResult> checks = new ArrayList<>();

            // A. Structural validity of all POSTED journals
            checks.addAll(oracle.checkJournalStructure(conn));

            // B. Debit total == Credit total
            checks.addAll(oracle.checkDebitCreditEquality(conn));

            // C. Balance snapshots reconstruct accurately from immutable POSTED journals
            checks.addAll(oracle.checkSnapshotIntegrity(conn, accountA.getId(), accountB.getId()));

            // D. Available balances non-negative
            checks.add(oracle.checkAvailableBalance(conn, accountA.getId()));
            checks.add(oracle.checkAvailableBalance(conn, accountB.getId()));

            // E. Conservation of money across internal transfer pair
            checks.add(oracle.checkInternalTransferConservation(conn, accountA.getId(), accountB.getId(), totalInitialMoney));

            assertThat(checks).allMatch(InvariantCheckResult::passed);
        }
    }
}
