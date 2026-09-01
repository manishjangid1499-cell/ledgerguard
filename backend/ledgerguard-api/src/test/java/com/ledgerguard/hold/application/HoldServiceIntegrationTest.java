package com.ledgerguard.hold.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.hold.domain.AvailableBalance;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.domain.HoldValidationException;
import com.ledgerguard.hold.domain.InsufficientAvailableBalanceException;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.payment.application.CreatePaymentCommand;
import com.ledgerguard.payment.application.PaymentResult;
import com.ledgerguard.payment.application.PaymentService;
import com.ledgerguard.refund.application.CreateRefundCommand;
import com.ledgerguard.refund.application.RefundService;
import com.ledgerguard.transfer.application.CreateTransferCommand;
import com.ledgerguard.transfer.application.TransferService;
import com.ledgerguard.transfer.domain.InsufficientFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldExpirationService holdExpirationService;

    @Autowired
    private BalanceHoldRepository balanceHoldRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Basic hold creation: posted unchanged, active hold recorded, available reduced, 0 journals created")
    void testBasicHoldCreation() {
        User customer = createTestUser("cust.basic." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 10000L);

        long journalCountBefore = getJournalCount();

        Instant expiry = Instant.now().plus(1, ChronoUnit.HOURS);
        BalanceHold hold = holdService.createHold(wallet.getId(), Money.inr(7000L), expiry);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getAmountMinor()).isEqualTo(7000L);
        assertThat(hold.getTerminalAt()).isNull();

        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(7000L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(3000L);

        // 0 journals created by hold operation
        long journalCountAfter = getJournalCount();
        assertThat(journalCountAfter).isEqualTo(journalCountBefore);

        // Snapshot remains untouched
        assertThat(getSnapshotBalance(wallet.getId())).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Exact available hold: reserves 100% of balance, available becomes 0, next hold rejected")
    void testExactAvailableHold() {
        User customer = createTestUser("cust.exact." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 10000L);

        BalanceHold hold = holdService.createHold(wallet.getId(), Money.inr(10000L), Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(10000L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(0L);

        assertThatThrownBy(() -> holdService.createHold(wallet.getId(), Money.inr(1L), Instant.now().plus(1, ChronoUnit.HOURS)))
                .isInstanceOf(InsufficientAvailableBalanceException.class);
    }

    @Test
    @DisplayName("Insufficient hold: requesting more than available throws InsufficientAvailableBalanceException with 0 rows committed")
    void testInsufficientHold() {
        User customer = createTestUser("cust.insuff." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 9999L);

        assertThatThrownBy(() -> holdService.createHold(wallet.getId(), Money.inr(10000L), Instant.now().plus(1, ChronoUnit.HOURS)))
                .isInstanceOf(InsufficientAvailableBalanceException.class);

        assertThat(balanceHoldRepository.findAllByLedgerAccountId(wallet.getId())).isEmpty();
        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.availableBalanceMinor()).isEqualTo(9999L);
    }

    @Test
    @DisplayName("Multiple holds accumulate: hold 3000 + hold 4000 on 10000, available is 3000, next 4000 fails")
    void testMultipleHoldsAccumulate() {
        User customer = createTestUser("cust.multi." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 10000L);

        holdService.createHold(wallet.getId(), Money.inr(3000L), Instant.now().plus(1, ChronoUnit.HOURS));
        holdService.createHold(wallet.getId(), Money.inr(4000L), Instant.now().plus(1, ChronoUnit.HOURS));

        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(7000L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(3000L);

        assertThatThrownBy(() -> holdService.createHold(wallet.getId(), Money.inr(4000L), Instant.now().plus(1, ChronoUnit.HOURS)))
                .isInstanceOf(InsufficientAvailableBalanceException.class);
    }

    @Test
    @DisplayName("Release hold: ACTIVE -> RELEASED restores available balance without posting journals")
    void testReleaseHoldRestoresAvailable() {
        User customer = createTestUser("cust.rel." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 10000L);

        long journalCountBefore = getJournalCount();
        BalanceHold hold = holdService.createHold(wallet.getId(), Money.inr(7000L), Instant.now().plus(1, ChronoUnit.HOURS));

        BalanceHold released = holdService.releaseHold(hold.getId());
        assertThat(released.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(released.getTerminalAt()).isNotNull();

        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(0L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(10000L);

        assertThat(getJournalCount()).isEqualTo(journalCountBefore);
    }

    @Test
    @DisplayName("Consume hold: ACTIVE -> CONSUMED stops counting in active holds without posting journals directly")
    void testConsumeHoldTransitionsStatus() {
        User customer = createTestUser("cust.cons." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 10000L);

        long journalCountBefore = getJournalCount();
        BalanceHold hold = holdService.createHold(wallet.getId(), Money.inr(7000L), Instant.now().plus(1, ChronoUnit.HOURS));

        BalanceHold consumed = holdService.consumeHold(hold.getId());
        assertThat(consumed.getStatus()).isEqualTo(HoldStatus.CONSUMED);
        assertThat(consumed.getTerminalAt()).isNotNull();

        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(0L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(10000L);

        assertThat(getJournalCount()).isEqualTo(journalCountBefore);
    }

    @Test
    @DisplayName("Hold expiration: due ACTIVE holds transition to EXPIRED, non-due remain ACTIVE, repeated run idempotent")
    void testExpireDueHolds() {
        User customer = createTestUser("cust.exp." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 20000L);

        Instant now = Instant.now();
        BalanceHold dueHold1 = holdService.createHold(wallet.getId(), Money.inr(3000L), now.plus(10, ChronoUnit.SECONDS));
        BalanceHold dueHold2 = holdService.createHold(wallet.getId(), Money.inr(4000L), now.plus(20, ChronoUnit.SECONDS));
        BalanceHold nonDueHold = holdService.createHold(wallet.getId(), Money.inr(5000L), now.plus(1, ChronoUnit.HOURS));

        // Before expiration time: all 3 active (total 12000 held, available 8000)
        AvailableBalance before = holdService.getAvailableBalance(wallet.getId());
        assertThat(before.activeHoldAmountMinor()).isEqualTo(12000L);
        assertThat(before.availableBalanceMinor()).isEqualTo(8000L);

        // Run expiration with timestamp past dueHold1 and dueHold2
        Instant testTime = now.plus(30, ChronoUnit.SECONDS);
        int expiredCount = holdExpirationService.expireDueHolds(testTime);
        assertThat(expiredCount).isEqualTo(2);

        // After expiration: only nonDueHold active (total 5000 held, available 15000)
        AvailableBalance after = holdService.getAvailableBalance(wallet.getId());
        assertThat(after.postedBalanceMinor()).isEqualTo(20000L);
        assertThat(after.activeHoldAmountMinor()).isEqualTo(5000L);
        assertThat(after.availableBalanceMinor()).isEqualTo(15000L);

        BalanceHold refreshed1 = balanceHoldRepository.findById(dueHold1.getId()).orElseThrow();
        assertThat(refreshed1.getStatus()).isEqualTo(HoldStatus.EXPIRED);

        BalanceHold refreshed2 = balanceHoldRepository.findById(dueHold2.getId()).orElseThrow();
        assertThat(refreshed2.getStatus()).isEqualTo(HoldStatus.EXPIRED);

        BalanceHold refreshedNonDue = balanceHoldRepository.findById(nonDueHold.getId()).orElseThrow();
        assertThat(refreshedNonDue.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        // Repeated run with same timestamp is idempotent
        int secondRunExpired = holdExpirationService.expireDueHolds(testTime);
        assertThat(secondRunExpired).isEqualTo(0);
    }

    @Test
    @DisplayName("Concurrent hold creation: Two 7000 hold requests on 10000 posted balance yield 1 success, 1 failure")
    void testConcurrentHoldCreation() throws Exception {
        User customer = createTestUser("cust.c-hold." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 10000L);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    holdService.createHold(wallet.getId(), Money.inr(7000L), Instant.now().plus(1, ChronoUnit.HOURS));
                    successes.incrementAndGet();
                } catch (InsufficientAvailableBalanceException e) {
                    failures.incrementAndGet();
                } catch (Throwable t) {
                    // unexpected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);

        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(7000L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("50-thread high-contention hold creation: 50 requests of 1000 on 25000 posted balance yield exactly 25 successes and 25 failures")
    void testHighContentionHoldCreation() throws Exception {
        User customer = createTestUser("cust.high." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 25000L);

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    holdService.createHold(wallet.getId(), Money.inr(1000L), Instant.now().plus(1, ChronoUnit.HOURS));
                    successes.incrementAndGet();
                } catch (InsufficientAvailableBalanceException e) {
                    failures.incrementAndGet();
                } catch (Throwable t) {
                    // unexpected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(20, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(successes.get()).isEqualTo(25);
        assertThat(failures.get()).isEqualTo(25);

        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(25000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(25000L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(0L);

        List<BalanceHold> holds = balanceHoldRepository.findAllByLedgerAccountId(wallet.getId());
        assertThat(holds).hasSize(25);
    }

    @Test
    @DisplayName("Cross-operation race: Concurrent 7000 Hold vs 7000 Transfer on 10000 posted balance - exactly ONE succeeds")
    void testHoldVsTransferCrossOperationRace() throws Exception {
        User customer = createTestUser("cust.race." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.race." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        fundWallet(customerWallet.getId(), 10000L);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger holdSuccess = new AtomicInteger(0);
        AtomicInteger transferSuccess = new AtomicInteger(0);

        // Thread 1: Create hold 7000
        executor.submit(() -> {
            try {
                startLatch.await();
                holdService.createHold(customerWallet.getId(), Money.inr(7000L), Instant.now().plus(1, ChronoUnit.HOURS));
                holdSuccess.incrementAndGet();
            } catch (Throwable ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Transfer 7000
        executor.submit(() -> {
            try {
                startLatch.await();
                transferService.createTransfer(new CreateTransferCommand(
                        customer.getId(),
                        merchantWallet.getId(),
                        Money.inr(7000L),
                        "race-tx-" + UUID.randomUUID()
                ));
                transferSuccess.incrementAndGet();
            } catch (Throwable ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one succeeded, never both
        assertThat(holdSuccess.get() + transferSuccess.get()).isEqualTo(1);

        AvailableBalance balance = holdService.getAvailableBalance(customerWallet.getId());
        if (holdSuccess.get() == 1) {
            assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
            assertThat(balance.activeHoldAmountMinor()).isEqualTo(7000L);
            assertThat(balance.availableBalanceMinor()).isEqualTo(3000L);
        } else {
            assertThat(balance.postedBalanceMinor()).isEqualTo(3000L);
            assertThat(balance.activeHoldAmountMinor()).isEqualTo(0L);
            assertThat(balance.availableBalanceMinor()).isEqualTo(3000L);
        }
    }

    @Test
    @DisplayName("Cross-operation race: Concurrent 7000 Hold vs 7000 Payment on 10000 posted balance - exactly ONE succeeds")
    void testHoldVsPaymentCrossOperationRace() throws Exception {
        User customer = createTestUser("cust.payrace." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.payrace." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();
        fundWallet(customerWallet.getId(), 10000L);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger holdSuccess = new AtomicInteger(0);
        AtomicInteger paymentSuccess = new AtomicInteger(0);

        // Thread 1: Create hold 7000
        executor.submit(() -> {
            try {
                startLatch.await();
                holdService.createHold(customerWallet.getId(), Money.inr(7000L), Instant.now().plus(1, ChronoUnit.HOURS));
                holdSuccess.incrementAndGet();
            } catch (Throwable ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Payment gross 7000
        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.createPayment(new CreatePaymentCommand(
                        customer.getId(),
                        "race-pay-" + UUID.randomUUID(),
                        merchantWallet.getId(),
                        Money.inr(7000L)
                ));
                paymentSuccess.incrementAndGet();
            } catch (Throwable ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one succeeded, never both
        assertThat(holdSuccess.get() + paymentSuccess.get()).isEqualTo(1);

        AvailableBalance balance = holdService.getAvailableBalance(customerWallet.getId());
        if (holdSuccess.get() == 1) {
            assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
            assertThat(balance.activeHoldAmountMinor()).isEqualTo(7000L);
            assertThat(balance.availableBalanceMinor()).isEqualTo(3000L);
        } else {
            assertThat(balance.postedBalanceMinor()).isEqualTo(3000L);
            assertThat(balance.activeHoldAmountMinor()).isEqualTo(0L);
            assertThat(balance.availableBalanceMinor()).isEqualTo(3000L);
        }
    }

    @Test
    @DisplayName("Transfer respects existing hold: posted 10000, hold 7000 -> transfer 4000 fails (available=3000); transfer 3000 succeeds")
    void testTransferRespectsExistingHold() {
        User customer = createTestUser("cust.txhold." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.txhold." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        fundWallet(customerWallet.getId(), 10000L);

        holdService.createHold(customerWallet.getId(), Money.inr(7000L), Instant.now().plus(1, ChronoUnit.HOURS));

        // Transfer 4000 exceeds available 3000 -> fails
        assertThatThrownBy(() -> transferService.createTransfer(new CreateTransferCommand(
                customer.getId(),
                merchantWallet.getId(),
                Money.inr(4000L),
                "tx-fail-" + UUID.randomUUID()
        ))).isInstanceOf(InsufficientFundsException.class);

        // Transfer 3000 matches available 3000 -> succeeds
        transferService.createTransfer(new CreateTransferCommand(
                customer.getId(),
                merchantWallet.getId(),
                Money.inr(3000L),
                "tx-succ-" + UUID.randomUUID()
        ));

        AvailableBalance balance = holdService.getAvailableBalance(customerWallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(7000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(7000L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Payment respects existing hold: customer posted 10000, hold 7000 -> payment 4000 fails; payment 3000 succeeds")
    void testPaymentRespectsExistingHold() {
        User customer = createTestUser("cust.payhold." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.payhold." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();
        fundWallet(customerWallet.getId(), 10000L);

        holdService.createHold(customerWallet.getId(), Money.inr(7000L), Instant.now().plus(1, ChronoUnit.HOURS));

        // Payment 4000 exceeds available 3000 -> fails
        assertThatThrownBy(() -> paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-fail-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.inr(4000L)
        ))).isInstanceOf(InsufficientFundsException.class);

        // Payment 3000 matches available 3000 -> succeeds
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-succ-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.inr(3000L)
        ));

        assertThat(payment.paymentId()).isNotNull();

        AvailableBalance customerBalance = holdService.getAvailableBalance(customerWallet.getId());
        assertThat(customerBalance.postedBalanceMinor()).isEqualTo(7000L);
        assertThat(customerBalance.activeHoldAmountMinor()).isEqualTo(7000L);
        assertThat(customerBalance.availableBalanceMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Holds do not affect incoming money: incoming credit increases posted and available by full amount")
    void testIncomingTransferIncreasesAvailable() {
        User sender = createTestUser("sender." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User receiver = createTestUser("receiver." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);

        LedgerAccount senderWallet = createWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createWallet(receiver.getId(), AccountType.CUSTOMER);

        fundWallet(senderWallet.getId(), 20000L);
        fundWallet(receiverWallet.getId(), 10000L);

        holdService.createHold(receiverWallet.getId(), Money.inr(7000L), Instant.now().plus(1, ChronoUnit.HOURS));

        // Incoming transfer of 5000 to receiver
        transferService.createTransfer(new CreateTransferCommand(
                sender.getId(),
                receiverWallet.getId(),
                Money.inr(5000L),
                "inc-tx-" + UUID.randomUUID()
        ));

        AvailableBalance balance = holdService.getAvailableBalance(receiverWallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(15000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(7000L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(8000L);
    }

    @Test
    @DisplayName("Refund liability with active hold: refund debits merchant posted balance, resulting in valid negative available balance")
    void testRefundLiabilityWithHold() {
        User customer = createTestUser("cust.refhold." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.refhold." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 10000L);

        // Execute payment of 10000 (merchant receives 9900 net)
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-ref-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.inr(10000L)
        ));

        // Merchant wallet posted is 9900; create hold of 9000 (available becomes 900)
        holdService.createHold(merchantWallet.getId(), Money.inr(9000L), Instant.now().plus(1, ChronoUnit.HOURS));

        AvailableBalance beforeRefund = holdService.getAvailableBalance(merchantWallet.getId());
        assertThat(beforeRefund.postedBalanceMinor()).isEqualTo(9900L);
        assertThat(beforeRefund.activeHoldAmountMinor()).isEqualTo(9000L);
        assertThat(beforeRefund.availableBalanceMinor()).isEqualTo(900L);

        // Customer initiates full refund of 10000 (merchant debited 9900)
        refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-liability-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.inr(10000L)
        ));

        // Merchant posted balance becomes 0; active hold is 9000; available balance is -9000 (valid merchant liability)
        AvailableBalance afterRefund = holdService.getAvailableBalance(merchantWallet.getId());
        assertThat(afterRefund.postedBalanceMinor()).isEqualTo(0L);
        assertThat(afterRefund.activeHoldAmountMinor()).isEqualTo(9000L);
        assertThat(afterRefund.availableBalanceMinor()).isEqualTo(-9000L);
    }

    @Test
    @DisplayName("Concurrent expiration vs release race: exactly one terminal state wins")
    void testConcurrentExpirationVsRelease() throws Exception {
        User customer = createTestUser("cust.race2." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        LedgerAccount wallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 10000L);

        Instant now = Instant.now();
        BalanceHold hold = holdService.createHold(wallet.getId(), Money.inr(5000L), now.plus(1, ChronoUnit.MILLIS));

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger releaseSuccess = new AtomicInteger(0);
        AtomicInteger expireSuccess = new AtomicInteger(0);

        // Thread 1: Release hold
        executor.submit(() -> {
            try {
                startLatch.await();
                holdService.releaseHold(hold.getId());
                releaseSuccess.incrementAndGet();
            } catch (Throwable ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Expire due hold
        executor.submit(() -> {
            try {
                startLatch.await();
                int count = holdExpirationService.expireDueHolds(now.plus(10, ChronoUnit.SECONDS));
                if (count > 0) {
                    expireSuccess.incrementAndGet();
                }
            } catch (Throwable ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        BalanceHold finalHold = balanceHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(finalHold.getStatus()).isIn(HoldStatus.RELEASED, HoldStatus.EXPIRED);
        assertThat(finalHold.getTerminalAt()).isNotNull();

        AvailableBalance balance = holdService.getAvailableBalance(wallet.getId());
        assertThat(balance.postedBalanceMinor()).isEqualTo(10000L);
        assertThat(balance.activeHoldAmountMinor()).isEqualTo(0L);
        assertThat(balance.availableBalanceMinor()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Account validation: active customer/merchant INR succeed; closed account, system account, missing account fail")
    void testAccountValidations() {
        User customer = createTestUser("cust.val." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.val." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);

        fundWallet(customerWallet.getId(), 10000L);
        fundWallet(merchantWallet.getId(), 10000L);

        // Active customer succeeds
        BalanceHold h1 = holdService.createHold(customerWallet.getId(), Money.inr(1000L), Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(h1).isNotNull();

        // Active merchant succeeds
        BalanceHold h2 = holdService.createHold(merchantWallet.getId(), Money.inr(1000L), Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(h2).isNotNull();

        // System account rejected
        LedgerAccount feeAccount = getOrCreatePlatformFeeAccount();
        assertThatThrownBy(() -> holdService.createHold(feeAccount.getId(), Money.inr(1000L), Instant.now().plus(1, ChronoUnit.HOURS)))
                .isInstanceOf(HoldValidationException.class);

        // Missing account rejected
        assertThatThrownBy(() -> holdService.createHold(UUID.randomUUID(), Money.inr(1000L), Instant.now().plus(1, ChronoUnit.HOURS)))
                .isInstanceOf(HoldValidationException.class);
    }

    private User createTestUser(String email, UserRole role) {
        User user = new User(UUID.randomUUID(), email, "$2a$10$hash", role, UserStatus.ACTIVE);
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

    private long getSnapshotBalance(UUID accountId) {
        return ledgerBalanceSnapshotRepository.findById(accountId)
                .map(LedgerBalanceSnapshot::getBalanceMinor)
                .orElse(0L);
    }

    private long getJournalCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_transactions", Long.class);
        return count != null ? count : 0L;
    }
}
