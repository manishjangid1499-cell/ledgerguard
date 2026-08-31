package com.ledgerguard.transfer.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.idempotency.domain.IdempotencyConflictException;
import com.ledgerguard.idempotency.domain.IdempotencyRecord;
import com.ledgerguard.idempotency.domain.IdempotencyStatus;
import com.ledgerguard.idempotency.infrastructure.IdempotencyRecordRepository;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.JournalEntry;
import com.ledgerguard.ledger.domain.JournalTransaction;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.JournalEntryRepository;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.transfer.domain.Transfer;
import com.ledgerguard.transfer.domain.TransferDestinationNotFoundException;
import com.ledgerguard.transfer.domain.TransferValidationException;
import com.ledgerguard.transfer.infrastructure.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("CUSTOMER to CUSTOMER transfer posts balanced double-entry journal and updates balance snapshots")
    void customerToCustomerTransfer() {
        UUID senderId = createTestUser("CUSTOMER");
        UUID receiverId = createTestUser("CUSTOMER");
        LedgerAccount senderWallet = createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiverId, AccountType.CUSTOMER);

        // Pre-fund sender with 50,000 INR
        fundWallet(senderWallet.getId(), 50000L);
        assertThat(getSnapshotBalance(senderWallet.getId())).isEqualTo(50000L);
        assertThat(getSnapshotBalance(receiverWallet.getId())).isEqualTo(0L);

        String key = "trf-key-c2c-" + UUID.randomUUID();
        CreateTransferCommand command = CreateTransferCommand.of(
                senderId,
                receiverWallet.getId(),
                Money.inr(10000L),
                key
        );

        TransferResult result = transferService.createTransfer(command);

        assertThat(result.replayed()).isFalse();
        assertThat(result.sourceLedgerAccountId()).isEqualTo(senderWallet.getId());
        assertThat(result.destinationLedgerAccountId()).isEqualTo(receiverWallet.getId());
        assertThat(result.amountMinor()).isEqualTo(10000L);
        assertThat(result.currency()).isEqualTo("INR");

        // Verify Transfer record
        Transfer transfer = transferRepository.findById(result.transferId()).orElseThrow();
        assertThat(transfer.getInitiatedByUserId()).isEqualTo(senderId);
        assertThat(transfer.getSourceLedgerAccountId()).isEqualTo(senderWallet.getId());
        assertThat(transfer.getDestinationLedgerAccountId()).isEqualTo(receiverWallet.getId());
        assertThat(transfer.getAmountMinor()).isEqualTo(10000L);
        assertThat(transfer.getCurrency()).isEqualTo("INR");
        assertThat(transfer.getJournalTransactionId()).isEqualTo(result.journalTransactionId());

        // Verify Journal is POSTED and matches Transfer metadata exactly
        JournalTransaction journal = journalTransactionRepository.findById(result.journalTransactionId()).orElseThrow();
        assertThat(journal.getStatus().name()).isEqualTo("POSTED");
        assertThat(journal.getCurrency()).isEqualTo("INR");

        List<JournalEntry> entries = journalEntryRepository.findByJournalTransactionId(journal.getId());
        assertThat(entries).hasSize(2);
        JournalEntry debitEntry = entries.stream().filter(e -> e.getDirection() == EntryDirection.DEBIT).findFirst().orElseThrow();
        JournalEntry creditEntry = entries.stream().filter(e -> e.getDirection() == EntryDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debitEntry.getLedgerAccount().getId()).isEqualTo(transfer.getSourceLedgerAccountId());
        assertThat(creditEntry.getLedgerAccount().getId()).isEqualTo(transfer.getDestinationLedgerAccountId());
        assertThat(debitEntry.getAmountMinor()).isEqualTo(transfer.getAmountMinor());
        assertThat(creditEntry.getAmountMinor()).isEqualTo(transfer.getAmountMinor());

        // Verify Balance Snapshots: sender debited 10,000 (50k -> 40k), receiver credited 10,000 (0 -> 10k)
        assertThat(getSnapshotBalance(senderWallet.getId())).isEqualTo(40000L);
        assertThat(getSnapshotBalance(receiverWallet.getId())).isEqualTo(10000L);

        // Conservation of funds: total combined balance unchanged (50k)
        assertThat(getSnapshotBalance(senderWallet.getId()) + getSnapshotBalance(receiverWallet.getId())).isEqualTo(50000L);

        // Verify Idempotency Record stored transfer ID (NOT journal ID)
        IdempotencyRecord idemp = idempotencyRecordRepository
                .findByActorUserIdAndOperationAndIdempotencyKey(senderId, TransferService.OPERATION_NAMESPACE, key)
                .orElseThrow();
        assertThat(idemp.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(idemp.getResultId()).isEqualTo(transfer.getId());
        assertThat(idemp.getResultId()).isNotEqualTo(journal.getId());
    }

    @Test
    @DisplayName("CUSTOMER to MERCHANT transfer executes successfully")
    void customerToMerchantTransfer() {
        UUID senderId = createTestUser("CUSTOMER");
        UUID merchantId = createTestUser("MERCHANT");
        LedgerAccount senderWallet = createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createTestWallet(merchantId, AccountType.MERCHANT);

        fundWallet(senderWallet.getId(), 20000L);

        TransferResult result = transferService.createTransfer(CreateTransferCommand.of(
                senderId,
                merchantWallet.getId(),
                Money.inr(5000L),
                "key-c2m-" + UUID.randomUUID()
        ));

        assertThat(result.replayed()).isFalse();
        assertThat(getSnapshotBalance(senderWallet.getId())).isEqualTo(15000L);
        assertThat(getSnapshotBalance(merchantWallet.getId())).isEqualTo(5000L);
    }

    @Test
    @DisplayName("MERCHANT to CUSTOMER transfer executes successfully")
    void merchantToCustomerTransfer() {
        UUID merchantId = createTestUser("MERCHANT");
        UUID customerId = createTestUser("CUSTOMER");
        LedgerAccount merchantWallet = createTestWallet(merchantId, AccountType.MERCHANT);
        LedgerAccount customerWallet = createTestWallet(customerId, AccountType.CUSTOMER);

        fundWallet(merchantWallet.getId(), 30000L);

        TransferResult result = transferService.createTransfer(CreateTransferCommand.of(
                merchantId,
                customerWallet.getId(),
                Money.inr(12000L),
                "key-m2c-" + UUID.randomUUID()
        ));

        assertThat(result.replayed()).isFalse();
        assertThat(getSnapshotBalance(merchantWallet.getId())).isEqualTo(18000L);
        assertThat(getSnapshotBalance(customerWallet.getId())).isEqualTo(12000L);
    }

    @Test
    @DisplayName("MERCHANT to MERCHANT transfer between two distinct merchants executes successfully")
    void merchantToMerchantTransfer() {
        UUID m1 = createTestUser("MERCHANT");
        UUID m2 = createTestUser("MERCHANT");
        LedgerAccount w1 = createTestWallet(m1, AccountType.MERCHANT);
        LedgerAccount w2 = createTestWallet(m2, AccountType.MERCHANT);

        fundWallet(w1.getId(), 100000L);

        TransferResult result = transferService.createTransfer(CreateTransferCommand.of(
                m1,
                w2.getId(),
                Money.inr(45000L),
                "key-m2m-" + UUID.randomUUID()
        ));

        assertThat(result.replayed()).isFalse();
        assertThat(getSnapshotBalance(w1.getId())).isEqualTo(55000L);
        assertThat(getSnapshotBalance(w2.getId())).isEqualTo(45000L);
    }

    @Test
    @DisplayName("Transfers to system accounts (PSP_CLEARING, PLATFORM_RESERVE, PLATFORM_FEES) are rejected")
    void systemDestinationRejected() {
        UUID senderId = createTestUser("CUSTOMER");
        createTestWallet(senderId, AccountType.CUSTOMER);

        LedgerAccount clearing = createSystemAccount(AccountType.PSP_CLEARING);
        LedgerAccount reserve = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount fees = createSystemAccount(AccountType.PLATFORM_FEES);

        long initialTransferCount = transferRepository.count();
        long initialJournalCount = journalTransactionRepository.count();

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, clearing.getId(), Money.inr(1000L), "key-sys-1"
        ))).isInstanceOf(TransferValidationException.class).hasMessageContaining("system ledger accounts");

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, reserve.getId(), Money.inr(1000L), "key-sys-2"
        ))).isInstanceOf(TransferValidationException.class).hasMessageContaining("system ledger accounts");

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, fees.getId(), Money.inr(1000L), "key-sys-3"
        ))).isInstanceOf(TransferValidationException.class).hasMessageContaining("system ledger accounts");

        assertThat(transferRepository.count()).isEqualTo(initialTransferCount);
        assertThat(journalTransactionRepository.count()).isEqualTo(initialJournalCount);
    }

    @Test
    @DisplayName("Closed source or destination wallet is rejected")
    void closedAccountRejected() {
        UUID senderId = createTestUser("CUSTOMER");
        UUID receiverId = createTestUser("CUSTOMER");
        LedgerAccount senderWallet = createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiverId, AccountType.CUSTOMER);

        // Close sender
        jdbcTemplate.update("UPDATE ledger_accounts SET status = 'CLOSED' WHERE id = ?", senderWallet.getId());

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, receiverWallet.getId(), Money.inr(1000L), "key-closed-src"
        ))).isInstanceOf(TransferValidationException.class).hasMessageContaining("not active");

        // Re-open sender, close receiver
        jdbcTemplate.update("UPDATE ledger_accounts SET status = 'ACTIVE' WHERE id = ?", senderWallet.getId());
        jdbcTemplate.update("UPDATE ledger_accounts SET status = 'CLOSED' WHERE id = ?", receiverWallet.getId());

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, receiverWallet.getId(), Money.inr(1000L), "key-closed-dst"
        ))).isInstanceOf(TransferValidationException.class).hasMessageContaining("not active");
    }

    @Test
    @DisplayName("Nonexistent destination account is rejected without financial or idempotency side effects")
    void missingDestinationRejected() {
        UUID senderId = createTestUser("CUSTOMER");
        createTestWallet(senderId, AccountType.CUSTOMER);
        UUID nonexistent = UUID.randomUUID();

        long initialTransferCount = transferRepository.count();
        long initialJournalCount = journalTransactionRepository.count();
        String key = "key-missing-dst-" + UUID.randomUUID();

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, nonexistent, Money.inr(1000L), key
        ))).isInstanceOf(TransferDestinationNotFoundException.class).hasMessageContaining("not found");

        assertThat(transferRepository.count()).isEqualTo(initialTransferCount);
        assertThat(journalTransactionRepository.count()).isEqualTo(initialJournalCount);
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(
                senderId, TransferService.OPERATION_NAMESPACE, key)).isEmpty();
    }

    @Test
    @DisplayName("User A and User B with identical Idempotency-Key execute independently in actor-scoped namespaces")
    void differentActorsSameKeyIndependentExecution() {
        UUID userA = createTestUser("CUSTOMER");
        UUID userB = createTestUser("CUSTOMER");
        UUID destUser = createTestUser("CUSTOMER");
        LedgerAccount walletA = createTestWallet(userA, AccountType.CUSTOMER);
        LedgerAccount walletB = createTestWallet(userB, AccountType.CUSTOMER);
        LedgerAccount destWallet = createTestWallet(destUser, AccountType.CUSTOMER);

        fundWallet(walletA.getId(), 50000L);
        fundWallet(walletB.getId(), 50000L);

        String sharedKey = "shared-idempotency-key-123";

        TransferResult resultA = transferService.createTransfer(CreateTransferCommand.of(
                userA, destWallet.getId(), Money.inr(10000L), sharedKey
        ));
        TransferResult resultB = transferService.createTransfer(CreateTransferCommand.of(
                userB, destWallet.getId(), Money.inr(15000L), sharedKey
        ));

        assertThat(resultA.replayed()).isFalse();
        assertThat(resultB.replayed()).isFalse();
        assertThat(resultA.transferId()).isNotEqualTo(resultB.transferId());
        assertThat(resultA.amountMinor()).isEqualTo(10000L);
        assertThat(resultB.amountMinor()).isEqualTo(15000L);

        assertThat(getSnapshotBalance(walletA.getId())).isEqualTo(40000L);
        assertThat(getSnapshotBalance(walletB.getId())).isEqualTo(35000L);
        assertThat(getSnapshotBalance(destWallet.getId())).isEqualTo(25000L);
    }

    @Test
    @DisplayName("Self-transfer to same account is rejected without financial side effects")
    void selfTransferRejected() {
        UUID senderId = createTestUser("CUSTOMER");
        LedgerAccount wallet = createTestWallet(senderId, AccountType.CUSTOMER);

        long initialTransferCount = transferRepository.count();
        long initialJournalCount = journalTransactionRepository.count();

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, wallet.getId(), Money.inr(1000L), "key-self"
        ))).isInstanceOf(TransferValidationException.class).hasMessageContaining("Self-transfers");

        assertThat(transferRepository.count()).isEqualTo(initialTransferCount);
        assertThat(journalTransactionRepository.count()).isEqualTo(initialJournalCount);
    }

    @Test
    @DisplayName("Zero or negative transfer amount is rejected")
    void invalidAmountRejected() {
        UUID senderId = createTestUser("CUSTOMER");
        UUID receiverId = createTestUser("CUSTOMER");
        createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiverId, AccountType.CUSTOMER);

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, receiverWallet.getId(), Money.inr(0L), "key-zero"
        ))).isInstanceOf(TransferValidationException.class).hasMessageContaining("strictly positive");

        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, receiverWallet.getId(), Money.inr(-500L), "key-neg"
        ))).isInstanceOf(TransferValidationException.class).hasMessageContaining("strictly positive");
    }

    @Test
    @DisplayName("Sequential replay returns identical transfer result without repeating ledger posting or snapshot changes")
    void sequentialTransferReplay() {
        UUID senderId = createTestUser("CUSTOMER");
        UUID receiverId = createTestUser("CUSTOMER");
        LedgerAccount senderWallet = createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiverId, AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 50000L);

        String key = "key-replay-trf-" + UUID.randomUUID();
        CreateTransferCommand command = CreateTransferCommand.of(
                senderId,
                receiverWallet.getId(),
                Money.inr(15000L),
                key
        );

        // First execution
        TransferResult firstResult = transferService.createTransfer(command);
        assertThat(firstResult.replayed()).isFalse();

        long journalCount = journalTransactionRepository.count();
        long transferCount = transferRepository.count();
        long senderBalance = getSnapshotBalance(senderWallet.getId());
        long receiverBalance = getSnapshotBalance(receiverWallet.getId());

        // Second execution (replay)
        TransferResult replayResult = transferService.createTransfer(command);
        assertThat(replayResult.replayed()).isTrue();
        assertThat(replayResult.transferId()).isEqualTo(firstResult.transferId());
        assertThat(replayResult.journalTransactionId()).isEqualTo(firstResult.journalTransactionId());
        assertThat(replayResult.amountMinor()).isEqualTo(firstResult.amountMinor());

        // Zero additional financial mutations
        assertThat(journalTransactionRepository.count()).isEqualTo(journalCount);
        assertThat(transferRepository.count()).isEqualTo(transferCount);
        assertThat(getSnapshotBalance(senderWallet.getId())).isEqualTo(senderBalance);
        assertThat(getSnapshotBalance(receiverWallet.getId())).isEqualTo(receiverBalance);
    }

    @Test
    @DisplayName("Same key with changed amount or destination throws IdempotencyConflictException")
    void sameKeyChangedPayloadConflict() {
        UUID senderId = createTestUser("CUSTOMER");
        UUID r1 = createTestUser("CUSTOMER");
        UUID r2 = createTestUser("CUSTOMER");
        LedgerAccount senderWallet = createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount w1 = createTestWallet(r1, AccountType.CUSTOMER);
        LedgerAccount w2 = createTestWallet(r2, AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 50000L);

        String key = "key-conflict-trf-" + UUID.randomUUID();

        // First transfer: 10,000 to w1
        TransferResult firstResult = transferService.createTransfer(CreateTransferCommand.of(
                senderId, w1.getId(), Money.inr(10000L), key
        ));
        assertThat(firstResult.replayed()).isFalse();

        // Attempt 1: Same key, changed amount (20,000 to w1)
        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, w1.getId(), Money.inr(20000L), key
        ))).isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request fingerprint");

        // Attempt 2: Same key, changed destination (10,000 to w2)
        assertThatThrownBy(() -> transferService.createTransfer(CreateTransferCommand.of(
                senderId, w2.getId(), Money.inr(10000L), key
        ))).isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request fingerprint");

        // Original transfer and balances remain intact
        assertThat(getSnapshotBalance(senderWallet.getId())).isEqualTo(40000L);
        assertThat(getSnapshotBalance(w1.getId())).isEqualTo(10000L);
        assertThat(getSnapshotBalance(w2.getId())).isEqualTo(0L);
    }

    @Test
    @DisplayName("Different keys with identical transfer payload execute as independent transfers")
    void differentKeysIndependentExecution() {
        UUID senderId = createTestUser("CUSTOMER");
        UUID receiverId = createTestUser("CUSTOMER");
        LedgerAccount senderWallet = createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiverId, AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 50000L);

        TransferResult t1 = transferService.createTransfer(CreateTransferCommand.of(
                senderId, receiverWallet.getId(), Money.inr(10000L), "key-indep-1-" + UUID.randomUUID()
        ));
        TransferResult t2 = transferService.createTransfer(CreateTransferCommand.of(
                senderId, receiverWallet.getId(), Money.inr(10000L), "key-indep-2-" + UUID.randomUUID()
        ));

        assertThat(t1.replayed()).isFalse();
        assertThat(t2.replayed()).isFalse();
        assertThat(t1.transferId()).isNotEqualTo(t2.transferId());
        assertThat(t1.journalTransactionId()).isNotEqualTo(t2.journalTransactionId());

        // Total 20,000 transferred
        assertThat(getSnapshotBalance(senderWallet.getId())).isEqualTo(30000L);
        assertThat(getSnapshotBalance(receiverWallet.getId())).isEqualTo(20000L);
    }

    @Test
    @DisplayName("8 concurrent identical transfer requests execute underlying transfer exactly once")
    void concurrentIdenticalTransferRequests() throws Exception {
        UUID senderId = createTestUser("CUSTOMER");
        UUID receiverId = createTestUser("CUSTOMER");
        LedgerAccount senderWallet = createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiverId, AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 100000L);

        String key = "key-concurrent-trf-" + UUID.randomUUID();
        CreateTransferCommand command = CreateTransferCommand.of(
                senderId,
                receiverWallet.getId(),
                Money.inr(25000L),
                key
        );

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<TransferResult>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return transferService.createTransfer(command);
            }));
        }

        List<TransferResult> results = new ArrayList<>();
        for (Future<TransferResult> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        // Exactly one result had replayed = false, remaining had replayed = true
        long executedCount = results.stream().filter(r -> !r.replayed()).count();
        long replayedCount = results.stream().filter(TransferResult::replayed).count();
        assertThat(executedCount).isEqualTo(1);
        assertThat(replayedCount).isEqualTo(threadCount - 1);

        // All returned identical transfer ID and journal ID
        UUID expectedTransferId = results.get(0).transferId();
        UUID expectedJournalId = results.get(0).journalTransactionId();
        assertThat(results).allMatch(r -> r.transferId().equals(expectedTransferId));
        assertThat(results).allMatch(r -> r.journalTransactionId().equals(expectedJournalId));

        // Exactly one Transfer row committed
        assertThat(transferRepository.findAll()).filteredOn(t -> t.getId().equals(expectedTransferId)).hasSize(1);

        // Snapshot balance debited and credited exactly once (100k -> 75k, 0 -> 25k)
        assertThat(getSnapshotBalance(senderWallet.getId())).isEqualTo(75000L);
        assertThat(getSnapshotBalance(receiverWallet.getId())).isEqualTo(25000L);
    }

    @Test
    @DisplayName("Snapshot arithmetic overflow rolls back entire transaction (transfer, journal, idempotency claim)")
    void snapshotOverflowRollback() {
        UUID senderId = createTestUser("CUSTOMER");
        UUID receiverId = createTestUser("CUSTOMER");
        LedgerAccount senderWallet = createTestWallet(senderId, AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiverId, AccountType.CUSTOMER);

        // Set receiver snapshot to Long.MAX_VALUE
        jdbcTemplate.update("UPDATE ledger_balance_snapshots SET balance_minor = ? WHERE ledger_account_id = ?",
                Long.MAX_VALUE, receiverWallet.getId());

        long initialTransferCount = transferRepository.count();
        long initialJournalCount = journalTransactionRepository.count();
        long initialEntryCount = journalEntryRepository.count();

        String key = "key-overflow-" + UUID.randomUUID();
        CreateTransferCommand command = CreateTransferCommand.of(
                senderId,
                receiverWallet.getId(),
                Money.inr(1000L),
                key
        );

        assertThatThrownBy(() -> transferService.createTransfer(command))
                .isInstanceOf(Exception.class);

        // Entire transaction rolled back
        assertThat(transferRepository.count()).isEqualTo(initialTransferCount);
        assertThat(journalTransactionRepository.count()).isEqualTo(initialJournalCount);
        assertThat(journalEntryRepository.count()).isEqualTo(initialEntryCount);
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(
                senderId, TransferService.OPERATION_NAMESPACE, key)).isEmpty();
    }

    private void fundWallet(UUID walletAccountId, long amountMinor) {
        LedgerAccount reserve = createSystemAccount(AccountType.PLATFORM_RESERVE);
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserve.getId(), amountMinor),
                PostingLine.credit(walletAccountId, amountMinor)
        ));
    }

    private Long getSnapshotBalance(UUID ledgerAccountId) {
        return ledgerBalanceSnapshotRepository.findById(ledgerAccountId)
                .map(s -> s.getBalanceMinor())
                .orElse(null);
    }

    private LedgerAccount createTestWallet(UUID ownerUserId, AccountType type) {
        LedgerAccount account = (type == AccountType.CUSTOMER)
                ? LedgerAccount.createCustomerAccount(ownerUserId)
                : LedgerAccount.createMerchantAccount(ownerUserId);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private LedgerAccount createSystemAccount(AccountType type) {
        List<LedgerAccount> existing = ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getAccountType() == type && a.getOwnerUserId() == null)
                .toList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        LedgerAccount account = LedgerAccount.createSystemAccount(type);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private UUID createTestUser(String role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                id, "trf_svc_" + id + "@example.com", "$2a$10$dummyHashValueForTestingPurposeOnly", role, now, now
        );
        return id;
    }
}
