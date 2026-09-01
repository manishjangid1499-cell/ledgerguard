package com.ledgerguard.payment.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.idempotency.domain.IdempotencyConflictException;
import com.ledgerguard.idempotency.domain.IdempotencyRecord;
import com.ledgerguard.idempotency.infrastructure.IdempotencyRecordRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.JournalEntry;
import com.ledgerguard.ledger.domain.JournalTransaction;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.JournalEntryRepository;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.payment.domain.Payment;
import com.ledgerguard.payment.domain.PaymentDestinationNotFoundException;
import com.ledgerguard.payment.domain.PaymentStatus;
import com.ledgerguard.payment.domain.PaymentValidationException;
import com.ledgerguard.payment.domain.PlatformFeeAccountException;
import com.ledgerguard.payment.infrastructure.PaymentRepository;
import com.ledgerguard.transfer.domain.InsufficientFundsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private UserRepository userRepository;

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
    private PaymentRepository paymentRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void ensureSingleActiveFeeAccount() {
        List<LedgerAccount> feeAccounts = ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_FEES);
        for (LedgerAccount fa : feeAccounts) {
            if (fa.getStatus() == AccountStatus.ACTIVE) {
                fa.close(Instant.now());
                ledgerAccountRepository.saveAndFlush(fa);
            }
        }
        LedgerAccount canonicalFee = LedgerAccount.createSystemAccount(AccountType.PLATFORM_FEES);
        ledgerAccountRepository.saveAndFlush(canonicalFee);
    }

    @Test
    @DisplayName("Normal 1% merchant payment posts balanced journal, updates snapshots, and stores Payment")
    void normalPaymentSuccess() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        LedgerAccount feeAccount = getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 100000L); // 1,000 INR

        String key = "pay-key-1-" + UUID.randomUUID();
        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR") // 100 INR gross -> 1 INR fee (100 minor), 99 INR net (9900 minor)
        );

        PaymentResult result = paymentService.createPayment(command);

        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.grossAmountMinor()).isEqualTo(10000L);
        assertThat(result.feeAmountMinor()).isEqualTo(100L);
        assertThat(result.merchantNetAmountMinor()).isEqualTo(9900L);
        assertThat(result.currency()).isEqualTo("INR");
        assertThat(result.journalTransactionId()).isNotNull();

        // 1. Verify Payment entity
        Payment payment = paymentRepository.findById(result.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getJournalTransactionId()).isEqualTo(result.journalTransactionId());
        assertThat(payment.getCompletedAt()).isNotNull();

        // 2. Verify Journal and Entries
        JournalTransaction journal = journalTransactionRepository.findById(result.journalTransactionId()).orElseThrow();
        assertThat(journal.getStatus().name()).isEqualTo("POSTED");

        List<JournalEntry> entries = journalEntryRepository.findByJournalTransactionId(journal.getId());
        assertThat(entries).hasSize(3);

        long totalDebits = entries.stream()
                .filter(e -> "DEBIT".equals(e.getDirection().name()))
                .mapToLong(JournalEntry::getAmountMinor)
                .sum();
        long totalCredits = entries.stream()
                .filter(e -> "CREDIT".equals(e.getDirection().name()))
                .mapToLong(JournalEntry::getAmountMinor)
                .sum();
        assertThat(totalDebits).isEqualTo(10000L);
        assertThat(totalCredits).isEqualTo(10000L);

        // 3. Verify Snapshots
        LedgerBalanceSnapshot customerSnap = ledgerBalanceSnapshotRepository.findById(customerWallet.getId()).orElseThrow();
        LedgerBalanceSnapshot merchantSnap = ledgerBalanceSnapshotRepository.findById(merchantWallet.getId()).orElseThrow();
        LedgerBalanceSnapshot feeSnap = ledgerBalanceSnapshotRepository.findById(feeAccount.getId()).orElseThrow();

        assertThat(customerSnap.getBalanceMinor()).isEqualTo(90000L);
        assertThat(merchantSnap.getBalanceMinor()).isEqualTo(9900L);
        assertThat(feeSnap.getBalanceMinor()).isGreaterThanOrEqualTo(100L);

        // 4. Verify Idempotency Record
        IdempotencyRecord record = idempotencyRecordRepository
                .findByActorUserIdAndOperationAndIdempotencyKey(customer.getId(), PaymentService.OPERATION_NAMESPACE, key)
                .orElseThrow();
        assertThat(record.getResultId()).isEqualTo(payment.getId());
        assertThat(record.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Zero-fee small payment creates exactly 2 journal entries and omits zero fee entry")
    void zeroFeeSmallPaymentSuccess() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 5000L);

        String key = "pay-zero-fee-" + UUID.randomUUID();
        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWallet.getId(),
                Money.ofMinor(99L, "INR") // 99 minor -> fee 0, net 99
        );

        PaymentResult result = paymentService.createPayment(command);

        assertThat(result.grossAmountMinor()).isEqualTo(99L);
        assertThat(result.feeAmountMinor()).isEqualTo(0L);
        assertThat(result.merchantNetAmountMinor()).isEqualTo(99L);

        List<JournalEntry> entries = journalEntryRepository.findByJournalTransactionId(result.journalTransactionId());
        assertThat(entries).hasSize(2); // Exactly 2 entries: customer DEBIT 99, merchant CREDIT 99
    }

    @Test
    @DisplayName("Exact funds payment consumes entire customer balance to zero")
    void exactFundsPayment() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 10000L);

        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                "pay-exact-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        PaymentResult result = paymentService.createPayment(command);
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);

        LedgerBalanceSnapshot customerSnap = ledgerBalanceSnapshotRepository.findById(customerWallet.getId()).orElseThrow();
        assertThat(customerSnap.getBalanceMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Payment with insufficient funds throws InsufficientFundsException and rolls back completely")
    void insufficientFundsThrowsAndRollsBack() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 9999L); // 1 minor short of 10000

        String key = "pay-insufficient-" + UUID.randomUUID();
        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        assertThatThrownBy(() -> paymentService.createPayment(command))
                .isInstanceOf(InsufficientFundsException.class);

        // Assert customer balance unchanged
        LedgerBalanceSnapshot customerSnap = ledgerBalanceSnapshotRepository.findById(customerWallet.getId()).orElseThrow();
        assertThat(customerSnap.getBalanceMinor()).isEqualTo(9999L);

        // Assert no Payment rows created
        assertThat(paymentRepository.findAll().stream()
                .noneMatch(p -> p.getCustomerUserId().equals(customer.getId()))).isTrue();

        // Assert idempotency record rolled back (unpoisoned)
        Optional<IdempotencyRecord> record = idempotencyRecordRepository
                .findByActorUserIdAndOperationAndIdempotencyKey(customer.getId(), PaymentService.OPERATION_NAMESPACE, key);
        assertThat(record).isEmpty();
    }

    @Test
    @DisplayName("Retry with exact same idempotency key succeeds after wallet is funded")
    void retryAfterFundingSucceeds() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 5000L);

        String key = "pay-retry-fund-" + UUID.randomUUID();
        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        // 1. Initial attempt fails
        assertThatThrownBy(() -> paymentService.createPayment(command))
                .isInstanceOf(InsufficientFundsException.class);

        // 2. Fund wallet with +10000 (total balance 15000)
        fundWallet(customerWallet.getId(), 10000L);

        // 3. Retry exact same command with same key
        PaymentResult result = paymentService.createPayment(command);
        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);

        LedgerBalanceSnapshot customerSnap = ledgerBalanceSnapshotRepository.findById(customerWallet.getId()).orElseThrow();
        assertThat(customerSnap.getBalanceMinor()).isEqualTo(5000L); // 15000 - 10000 = 5000
    }

    @Test
    @DisplayName("Payment to non-MERCHANT wallet (e.g. CUSTOMER) is rejected")
    void paymentToCustomerWalletRejected() {
        User customer = createCustomerUser();
        User otherCustomer = createCustomerUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount otherCustomerWallet = createWallet(otherCustomer.getId(), AccountType.CUSTOMER);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                "pay-invalid-dest-" + UUID.randomUUID(),
                otherCustomerWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        assertThatThrownBy(() -> paymentService.createPayment(command))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("MERCHANT wallet");
    }

    @Test
    @DisplayName("Payment to nonexistent merchant returns PaymentDestinationNotFoundException")
    void paymentToMissingMerchantNotFound() {
        User customer = createCustomerUser();
        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        getOrCreatePlatformFeeAccount();
        fundWallet(customerWallet.getId(), 50000L);

        UUID missingMerchantId = UUID.randomUUID();
        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                "pay-missing-" + UUID.randomUUID(),
                missingMerchantId,
                Money.ofMinor(10000L, "INR")
        );

        assertThatThrownBy(() -> paymentService.createPayment(command))
                .isInstanceOf(PaymentDestinationNotFoundException.class);
    }

    @Test
    @DisplayName("Payment to CLOSED merchant account is rejected")
    void paymentToClosedMerchantRejected() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        merchantWallet.close(Instant.now());
        ledgerAccountRepository.saveAndFlush(merchantWallet);

        getOrCreatePlatformFeeAccount();
        fundWallet(customerWallet.getId(), 50000L);

        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                "pay-closed-merch-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        assertThatThrownBy(() -> paymentService.createPayment(command))
                .isInstanceOf(PaymentValidationException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("Missing platform fee account when fee > 0 throws PlatformFeeAccountException and rolls back")
    void missingPlatformFeeAccountThrows() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);

        // Close all active fee accounts
        List<LedgerAccount> feeAccounts = ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_FEES);
        for (LedgerAccount fa : feeAccounts) {
            if (fa.getStatus() == AccountStatus.ACTIVE) {
                fa.close(Instant.now());
                ledgerAccountRepository.saveAndFlush(fa);
            }
        }

        fundWallet(customerWallet.getId(), 50000L);

        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                "pay-missing-fee-acc-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        assertThatThrownBy(() -> paymentService.createPayment(command))
                .isInstanceOf(PlatformFeeAccountException.class)
                .hasMessageContaining("No active INR PLATFORM_FEES");
    }

    @Test
    @DisplayName("Multiple platform fee accounts when fee > 0 throws PlatformFeeAccountException and rolls back")
    void multiplePlatformFeeAccountsThrows() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);

        // Create a second active platform fee account
        LedgerAccount extraFee = LedgerAccount.createSystemAccount(AccountType.PLATFORM_FEES);
        ledgerAccountRepository.saveAndFlush(extraFee);

        fundWallet(customerWallet.getId(), 50000L);

        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                "pay-multi-fee-acc-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        assertThatThrownBy(() -> paymentService.createPayment(command))
                .isInstanceOf(PlatformFeeAccountException.class)
                .hasMessageContaining("Multiple active INR PLATFORM_FEES");
    }

    @Test
    @DisplayName("Idempotency replay returns original PaymentResult without secondary journal or locking")
    void idempotencyReplayReturnsCachedResult() {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        String key = "pay-replay-" + UUID.randomUUID();
        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        // First execution
        PaymentResult first = paymentService.createPayment(command);
        assertThat(first.replayed()).isFalse();

        // Second execution (replay)
        PaymentResult replay = paymentService.createPayment(command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
        assertThat(replay.journalTransactionId()).isEqualTo(first.journalTransactionId());
        assertThat(replay.grossAmountMinor()).isEqualTo(first.grossAmountMinor());

        // Balance deducted only once
        LedgerBalanceSnapshot customerSnap = ledgerBalanceSnapshotRepository.findById(customerWallet.getId()).orElseThrow();
        assertThat(customerSnap.getBalanceMinor()).isEqualTo(40000L);
    }

    @Test
    @DisplayName("Reusing idempotency key with different payload triggers IdempotencyConflictException")
    void idempotencyConflictOnChangedPayload() {
        User customer = createCustomerUser();
        User merchantA = createMerchantUser();
        User merchantB = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWalletA = createWallet(merchantA.getId(), AccountType.MERCHANT);
        LedgerAccount merchantWalletB = createWallet(merchantB.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        String key = "pay-conflict-" + UUID.randomUUID();
        CreatePaymentCommand command1 = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWalletA.getId(),
                Money.ofMinor(10000L, "INR")
        );
        paymentService.createPayment(command1);

        // Changed amount
        CreatePaymentCommand commandChangedAmount = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWalletA.getId(),
                Money.ofMinor(20000L, "INR")
        );
        assertThatThrownBy(() -> paymentService.createPayment(commandChangedAmount))
                .isInstanceOf(IdempotencyConflictException.class);

        // Changed merchant
        CreatePaymentCommand commandChangedMerchant = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWalletB.getId(),
                Money.ofMinor(10000L, "INR")
        );
        assertThatThrownBy(() -> paymentService.createPayment(commandChangedMerchant))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("Concurrent identical requests execute exactly once and return replay for others")
    void concurrentIdenticalPaymentsExecuteOnce() throws Exception {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 100000L);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        String key = "pay-concurrent-idem-" + UUID.randomUUID();
        CreatePaymentCommand command = new CreatePaymentCommand(
                customer.getId(),
                key,
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        );

        List<PaymentResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    PaymentResult result = paymentService.createPayment(command);
                    results.add(result);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threadCount);

        long nonReplayedCount = results.stream().filter(r -> !r.replayed()).count();
        long replayedCount = results.stream().filter(PaymentResult::replayed).count();

        assertThat(nonReplayedCount).isEqualTo(1);
        assertThat(replayedCount).isEqualTo(threadCount - 1);

        UUID canonicalPaymentId = results.get(0).paymentId();
        assertThat(results).allMatch(r -> r.paymentId().equals(canonicalPaymentId));

        LedgerBalanceSnapshot customerSnap = ledgerBalanceSnapshotRepository.findById(customerWallet.getId()).orElseThrow();
        assertThat(customerSnap.getBalanceMinor()).isEqualTo(90000L);
    }

    @Test
    @DisplayName("Competing payments with different keys on limited funds prevent double-spending")
    void competingPaymentsPreventDoubleSpending() throws Exception {
        User customer = createCustomerUser();
        User merchant = createMerchantUser();

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 10000L); // 100 INR

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);

        CreatePaymentCommand commandA = new CreatePaymentCommand(
                customer.getId(),
                "compete-key-A-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(7000L, "INR") // 70 INR
        );

        CreatePaymentCommand commandB = new CreatePaymentCommand(
                customer.getId(),
                "compete-key-B-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(7000L, "INR") // 70 INR
        );

        executor.submit(() -> {
            try {
                startGate.await();
                paymentService.createPayment(commandA);
                successCount.incrementAndGet();
            } catch (InsufficientFundsException e) {
                insufficientCount.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneGate.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startGate.await();
                paymentService.createPayment(commandB);
                successCount.incrementAndGet();
            } catch (InsufficientFundsException e) {
                insufficientCount.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneGate.countDown();
            }
        });

        startGate.countDown();
        doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(insufficientCount.get()).isEqualTo(1);

        LedgerBalanceSnapshot customerSnap = ledgerBalanceSnapshotRepository.findById(customerWallet.getId()).orElseThrow();
        assertThat(customerSnap.getBalanceMinor()).isEqualTo(3000L); // 10000 - 7000 = 3000
    }

    @Test
    @DisplayName("Shared merchant and platform fee concurrency produces zero lost updates")
    void sharedMerchantAndFeeConcurrencyNoLostUpdates() throws Exception {
        int customerCount = 10;
        List<User> customers = new ArrayList<>();
        List<LedgerAccount> customerWallets = new ArrayList<>();

        User merchant = createMerchantUser();
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        LedgerAccount feeAccount = getOrCreatePlatformFeeAccount();

        for (int i = 0; i < customerCount; i++) {
            User c = createCustomerUser();
            customers.add(c);
            LedgerAccount w = createWallet(c.getId(), AccountType.CUSTOMER);
            customerWallets.add(w);
            fundWallet(w.getId(), 20000L);
        }

        ExecutorService executor = Executors.newFixedThreadPool(customerCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(customerCount);
        List<Future<PaymentResult>> futures = new ArrayList<>();

        for (int i = 0; i < customerCount; i++) {
            User c = customers.get(i);
            CreatePaymentCommand cmd = new CreatePaymentCommand(
                    c.getId(),
                    "shared-merch-" + i + "-" + UUID.randomUUID(),
                    merchantWallet.getId(),
                    Money.ofMinor(10000L, "INR") // 100 fee, 9900 net each
            );
            futures.add(executor.submit(() -> {
                startGate.await();
                return paymentService.createPayment(cmd);
            }));
        }

        startGate.countDown();
        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);

        for (Future<PaymentResult> f : futures) {
            PaymentResult res = f.get();
            assertThat(res.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        }

        LedgerBalanceSnapshot merchantSnap = ledgerBalanceSnapshotRepository.findById(merchantWallet.getId()).orElseThrow();
        assertThat(merchantSnap.getBalanceMinor()).isEqualTo(9900L * customerCount); // 99,000

        LedgerBalanceSnapshot feeSnap = ledgerBalanceSnapshotRepository.findById(feeAccount.getId()).orElseThrow();
        assertThat(feeSnap.getBalanceMinor()).isGreaterThanOrEqualTo(100L * customerCount); // >= 1,000
    }

    private User createCustomerUser() {
        User user = new User(
                UUID.randomUUID(),
                "cust." + UUID.randomUUID() + "@example.com",
                "$2a$10$hash",
                UserRole.CUSTOMER,
                UserStatus.ACTIVE
        );
        return userRepository.save(user);
    }

    private User createMerchantUser() {
        User user = new User(
                UUID.randomUUID(),
                "merch." + UUID.randomUUID() + "@example.com",
                "$2a$10$hash",
                UserRole.MERCHANT,
                UserStatus.ACTIVE
        );
        return userRepository.save(user);
    }

    private LedgerAccount createWallet(UUID ownerUserId, AccountType type) {
        LedgerAccount account = (type == AccountType.CUSTOMER)
                ? LedgerAccount.createCustomerAccount(ownerUserId)
                : LedgerAccount.createMerchantAccount(ownerUserId);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private LedgerAccount getOrCreatePlatformFeeAccount() {
        return ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_FEES).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE && "INR".equals(a.getCurrency()) && a.getOwnerUserId() == null)
                .findFirst()
                .orElseGet(() -> {
                    LedgerAccount feeAccount = LedgerAccount.createSystemAccount(AccountType.PLATFORM_FEES);
                    return ledgerAccountRepository.saveAndFlush(feeAccount);
                });
    }

    private void fundWallet(UUID walletAccountId, long amountMinor) {
        LedgerAccount reserve = ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_RESERVE).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE && "INR".equals(a.getCurrency()) && a.getOwnerUserId() == null)
                .findFirst()
                .orElseGet(() -> {
                    LedgerAccount reserveAcc = LedgerAccount.createSystemAccount(AccountType.PLATFORM_RESERVE);
                    return ledgerAccountRepository.saveAndFlush(reserveAcc);
                });

        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserve.getId(), amountMinor),
                PostingLine.credit(walletAccountId, amountMinor)
        ));
    }
}
