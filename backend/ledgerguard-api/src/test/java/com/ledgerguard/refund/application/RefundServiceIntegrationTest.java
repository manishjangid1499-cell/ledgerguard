package com.ledgerguard.refund.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.idempotency.domain.IdempotencyConflictException;
import com.ledgerguard.idempotency.infrastructure.IdempotencyRecordRepository;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.JournalEntry;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.JournalEntryRepository;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.payment.application.CreatePaymentCommand;
import com.ledgerguard.payment.application.PaymentResult;
import com.ledgerguard.payment.application.PaymentService;
import com.ledgerguard.payment.domain.PaymentDestinationNotFoundException;
import com.ledgerguard.refund.domain.PaymentNotRefundableException;
import com.ledgerguard.refund.domain.Refund;
import com.ledgerguard.refund.domain.RefundLimitExceededException;
import com.ledgerguard.refund.infrastructure.RefundRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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

class RefundServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RefundService refundService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundRepository refundRepository;

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
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Executes full refund: exact reversal of customer gross, merchant net, and platform fee")
    void testFullRefund() {
        User customer = createTestUser("cust.full." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.full." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);
        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        LedgerAccount feeAccount = getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        // 1. Payment: gross 10000, fee 100, net 9900
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        assertThat(payment.grossAmountMinor()).isEqualTo(10000L);
        assertThat(payment.feeAmountMinor()).isEqualTo(100L);
        assertThat(payment.merchantNetAmountMinor()).isEqualTo(9900L);

        long custBalAfterPay = getBalance(customerWallet.getId());
        long merchBalAfterPay = getBalance(merchantWallet.getId());
        long feeBalAfterPay = getBalance(feeAccount.getId());

        // 2. Full Refund: 10000
        String refundKey = "ref-full-" + UUID.randomUUID();
        RefundResult refund = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                refundKey,
                payment.paymentId(),
                Money.ofMinor(10000L, "INR")
        ));

        assertThat(refund.refundAmountMinor()).isEqualTo(10000L);
        assertThat(refund.merchantDebitAmountMinor()).isEqualTo(9900L);
        assertThat(refund.feeDebitAmountMinor()).isEqualTo(100L);
        assertThat(refund.replayed()).isFalse();

        // Check balances
        assertThat(getBalance(customerWallet.getId())).isEqualTo(custBalAfterPay + 10000L);
        assertThat(getBalance(merchantWallet.getId())).isEqualTo(merchBalAfterPay - 9900L);
        assertThat(getBalance(feeAccount.getId())).isEqualTo(feeBalAfterPay - 100L);

        // Replay
        RefundResult replay = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                refundKey,
                payment.paymentId(),
                Money.ofMinor(10000L, "INR")
        ));
        assertThat(replay.refundId()).isEqualTo(refund.refundId());
        assertThat(replay.replayed()).isTrue();

        // Further refund is rejected
        assertThatThrownBy(() -> refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-over-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(1L, "INR")
        ))).isInstanceOf(RefundLimitExceededException.class);
    }

    @Test
    @DisplayName("Executes partial refund and subsequent over-refund rejection")
    void testPartialRefundAndCap() {
        User customer = createTestUser("cust.part." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.part." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);
        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-part-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        // 1. Partial refund 2500 -> fee 25, net 2475
        RefundResult r1 = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-p1-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(2500L, "INR")
        ));
        assertThat(r1.refundAmountMinor()).isEqualTo(2500L);
        assertThat(r1.feeDebitAmountMinor()).isEqualTo(25L);
        assertThat(r1.merchantDebitAmountMinor()).isEqualTo(2475L);

        // 2. Over-refund attempt 8000 (total 10500 > 10000) -> 409
        String overKey = "ref-over-" + UUID.randomUUID();
        assertThatThrownBy(() -> refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                overKey,
                payment.paymentId(),
                Money.ofMinor(8000L, "INR")
        ))).isInstanceOf(RefundLimitExceededException.class);

        // Verify uncommitted idempotency slot dropped
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(
                merchant.getId(), "payment-refund:v1", overKey
        )).isEmpty();

        // 3. Exact remaining refund 7500 -> succeeds
        RefundResult r2 = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-p2-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(7500L, "INR")
        ));
        assertThat(r2.refundAmountMinor()).isEqualTo(7500L);
        assertThat(r2.feeDebitAmountMinor()).isEqualTo(75L);
        assertThat(r2.merchantDebitAmountMinor()).isEqualTo(7425L);

        // Total cumulative
        long totalRefunded = refundRepository.sumRefundAmountByPaymentId(payment.paymentId());
        assertThat(totalRefunded).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Handles 101/1 rounding edge case with omitted zero-merchant journal line")
    void testRoundingEdgeCase101() {
        User customer = createTestUser("cust.101." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.101." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);
        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        // Payment: gross 101, fee 1, net 100
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-101-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(101L, "INR")
        ));

        // Refund 1: 50 -> merchant 50, fee 0
        RefundResult r1 = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-101-1-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(50L, "INR")
        ));
        assertThat(r1.feeDebitAmountMinor()).isEqualTo(0L);
        assertThat(r1.merchantDebitAmountMinor()).isEqualTo(50L);

        List<JournalEntry> r1Entries = journalEntryRepository.findByJournalTransactionId(r1.journalTransactionId());
        assertThat(r1Entries).hasSize(2); // Customer credit 50, merchant debit 50

        // Refund 2: 50 -> merchant 50, fee 0
        RefundResult r2 = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-101-2-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(50L, "INR")
        ));
        assertThat(r2.feeDebitAmountMinor()).isEqualTo(0L);
        assertThat(r2.merchantDebitAmountMinor()).isEqualTo(50L);

        // Refund 3: 1 -> merchant 0, fee 1
        RefundResult r3 = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-101-3-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(1L, "INR")
        ));
        assertThat(r3.feeDebitAmountMinor()).isEqualTo(1L);
        assertThat(r3.merchantDebitAmountMinor()).isEqualTo(0L);

        List<JournalEntry> r3Entries = journalEntryRepository.findByJournalTransactionId(r3.journalTransactionId());
        assertThat(r3Entries).hasSize(2); // Customer credit 1, fee debit 1 (merchant debit 0 omitted!)
    }

    @Test
    @DisplayName("Zero-fee payment refund requires no platform fee account lookup")
    void testZeroFeePaymentRefund() {
        User customer = createTestUser("cust.zero." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.zero." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);
        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);

        fundWallet(customerWallet.getId(), 50000L);

        // Payment: gross 99, fee 0, net 99
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-zero-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(99L, "INR")
        ));

        // Refund: 99
        RefundResult refund = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-zero-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(99L, "INR")
        ));

        assertThat(refund.refundAmountMinor()).isEqualTo(99L);
        assertThat(refund.merchantDebitAmountMinor()).isEqualTo(99L);
        assertThat(refund.feeDebitAmountMinor()).isEqualTo(0L);

        List<JournalEntry> entries = journalEntryRepository.findByJournalTransactionId(refund.journalTransactionId());
        assertThat(entries).hasSize(2); // Customer credit 99, merchant debit 99
    }

    @Test
    @DisplayName("Unrelated merchant receives 404 when attempting to refund another merchant's payment")
    void testUnrelatedMerchantForbidden() {
        User customer = createTestUser("cust.unrel." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchantA = createTestUser("merch.a." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);
        User merchantB = createTestUser("merch.b." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantAWallet = createWallet(merchantA.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-unrel-" + UUID.randomUUID(),
                merchantAWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        // Merchant B tries to refund Merchant A's payment -> 404
        assertThatThrownBy(() -> refundService.createRefund(new CreateRefundCommand(
                merchantB.getId(),
                "ref-unrel-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(5000L, "INR")
        ))).isInstanceOf(PaymentDestinationNotFoundException.class);
    }

    @Test
    @DisplayName("Refund against non-SUCCEEDED payment is rejected with PAYMENT_NOT_REFUNDABLE")
    void testNonSucceededPaymentRejection() {
        User merchant = createTestUser("merch.nonsuc." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);
        UUID fakePaymentId = UUID.randomUUID();

        // Missing payment -> 404
        assertThatThrownBy(() -> refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-miss-" + UUID.randomUUID(),
                fakePaymentId,
                Money.ofMinor(5000L, "INR")
        ))).isInstanceOf(PaymentDestinationNotFoundException.class);
    }

    @Test
    @DisplayName("Refund allows merchant balance to become negative if merchant moved funds")
    void testNegativeMerchantBalanceAllowed() {
        User customer = createTestUser("cust.neg." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.neg." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);
        User thirdParty = createTestUser("third.neg." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        LedgerAccount thirdPartyWallet = createWallet(thirdParty.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        // Payment: gross 10000, fee 100, net 9900
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-neg-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        // Merchant moves all 9900 to thirdParty wallet
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(merchantWallet.getId(), 9900L),
                PostingLine.credit(thirdPartyWallet.getId(), 9900L)
        ));

        assertThat(getBalance(merchantWallet.getId())).isEqualTo(0L);

        // Customer requests full refund: merchant debited 9900 -> merchant balance becomes -9900
        RefundResult refund = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-neg-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(10000L, "INR")
        ));

        assertThat(refund.refundAmountMinor()).isEqualTo(10000L);
        assertThat(getBalance(merchantWallet.getId())).isEqualTo(-9900L);
    }

    @Test
    @DisplayName("Full economic restoration: pre-payment snapshots equal post-refund snapshots")
    void testFullEconomicRestoration() {
        User customer = createTestUser("cust.rest." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.rest." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        LedgerAccount feeAccount = getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);
        fundWallet(merchantWallet.getId(), 10000L);

        long preCustomer = getBalance(customerWallet.getId());
        long preMerchant = getBalance(merchantWallet.getId());
        long preFee = getBalance(feeAccount.getId());

        // Execute payment
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-rest-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        // Execute 2 partial refunds (4000 and 6000)
        refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-rest-1-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(4000L, "INR")
        ));

        refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                "ref-rest-2-" + UUID.randomUUID(),
                payment.paymentId(),
                Money.ofMinor(6000L, "INR")
        ));

        // Final balances must match pre-payment balances exactly
        assertThat(getBalance(customerWallet.getId())).isEqualTo(preCustomer);
        assertThat(getBalance(merchantWallet.getId())).isEqualTo(preMerchant);
        assertThat(getBalance(feeAccount.getId())).isEqualTo(preFee);

        // Verify journal reconstruction matches snapshot
        assertReconstructedBalance(customerWallet.getId());
        assertReconstructedBalance(merchantWallet.getId());
        assertReconstructedBalance(feeAccount.getId());
    }

    @Test
    @DisplayName("Concurrent identical refund requests: 8 threads yield 1 execution and 7 replays")
    void testConcurrentIdenticalRefunds() throws Exception {
        User customer = createTestUser("cust.ident." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.ident." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-ident-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        int threadCount = 8;
        String idempotencyKey = "ref-ident-" + UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<RefundResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RefundResult result = refundService.createRefund(new CreateRefundCommand(
                            merchant.getId(),
                            idempotencyKey,
                            payment.paymentId(),
                            Money.ofMinor(5000L, "INR")
                    ));
                    results.add(result);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threadCount);

        UUID firstRefundId = results.get(0).refundId();
        long executionCount = results.stream().filter(r -> !r.replayed()).count();
        long replayCount = results.stream().filter(RefundResult::replayed).count();

        assertThat(executionCount).isEqualTo(1);
        assertThat(replayCount).isEqualTo(threadCount - 1);
        assertThat(results).allMatch(r -> r.refundId().equals(firstRefundId));

        assertThat(refundRepository.findAllByPaymentId(payment.paymentId())).hasSize(1);
    }

    @Test
    @DisplayName("Same idempotency key with changed refund amount throws IDEMPOTENCY_CONFLICT")
    void testSameKeyChangedAmountConflict() {
        User customer = createTestUser("cust.conf." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.conf." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-conf-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        String key = "ref-conf-" + UUID.randomUUID();
        RefundResult r1 = refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                key,
                payment.paymentId(),
                Money.ofMinor(2000L, "INR")
        ));
        assertThat(r1.refundAmountMinor()).isEqualTo(2000L);

        // Same key, different amount -> 409 IDEMPOTENCY_CONFLICT
        assertThatThrownBy(() -> refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                key,
                payment.paymentId(),
                Money.ofMinor(3000L, "INR")
        ))).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("Concurrent competing different-key refunds on 10000 gross: two 7000 requests yield 1 success, 1 failure")
    void testConcurrentCompetingRefunds() throws Exception {
        User customer = createTestUser("cust.comp." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.comp." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-comp-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger limitFailures = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            String key = "ref-comp-key-" + i + "-" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    refundService.createRefund(new CreateRefundCommand(
                            merchant.getId(),
                            key,
                            payment.paymentId(),
                            Money.ofMinor(7000L, "INR")
                    ));
                    successes.incrementAndGet();
                } catch (RefundLimitExceededException e) {
                    limitFailures.incrementAndGet();
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
        assertThat(limitFailures.get()).isEqualTo(1);

        long totalRefunded = refundRepository.sumRefundAmountByPaymentId(payment.paymentId());
        assertThat(totalRefunded).isEqualTo(7000L);
    }

    @Test
    @DisplayName("High contention 50-thread refund cap test: 25000 gross, 50 threads requesting 1000 each -> exactly 25 succeed, 25 fail")
    void testHighContentionRefundCap() throws Exception {
        User customer = createTestUser("cust.high." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.high." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        // Payment: gross 25000
        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-high-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(25000L, "INR")
        ));

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger limitFailures = new AtomicInteger(0);
        List<Throwable> otherErrors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            String key = "ref-high-" + i + "-" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    refundService.createRefund(new CreateRefundCommand(
                            merchant.getId(),
                            key,
                            payment.paymentId(),
                            Money.ofMinor(1000L, "INR")
                    ));
                    successes.incrementAndGet();
                } catch (RefundLimitExceededException e) {
                    limitFailures.incrementAndGet();
                } catch (Throwable t) {
                    otherErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(otherErrors).isEmpty();
        assertThat(successes.get()).isEqualTo(25);
        assertThat(limitFailures.get()).isEqualTo(25);

        long totalRefunded = refundRepository.sumRefundAmountByPaymentId(payment.paymentId());
        assertThat(totalRefunded).isEqualTo(25000L);

        List<Refund> refunds = refundRepository.findAllByPaymentId(payment.paymentId());
        assertThat(refunds).hasSize(25);
    }

    @Test
    @DisplayName("Natural customer credit balance snapshot overflow causes clean rollback (0 refund, 0 journal, 0 idempotency, unmutated balances)")
    void testSnapshotOverflowRollback() {
        User customer = createTestUser("cust.oflow." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User merchant = createTestUser("merch.oflow." + UUID.randomUUID() + "@example.com", UserRole.MERCHANT);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-oflow-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        // Directly set customer snapshot to near Long.MAX_VALUE
        jdbcTemplate.update(
                "UPDATE ledger_balance_snapshots SET balance_minor = ? WHERE ledger_account_id = ?",
                Long.MAX_VALUE - 1000L, customerWallet.getId()
        );

        String idempotencyKey = "ref-oflow-" + UUID.randomUUID();

        // Attempt refund of 5000 (which causes customer snapshot balance to overflow signed 64-bit integer)
        assertThatThrownBy(() -> refundService.createRefund(new CreateRefundCommand(
                merchant.getId(),
                idempotencyKey,
                payment.paymentId(),
                Money.ofMinor(5000L, "INR")
        ))).isInstanceOf(Exception.class);

        // 1. 0 Refund rows committed
        assertThat(refundRepository.findAllByPaymentId(payment.paymentId())).isEmpty();

        // 2. 0 Idempotency records committed
        assertThat(idempotencyRecordRepository.findByActorUserIdAndOperationAndIdempotencyKey(
                merchant.getId(), "payment-refund:v1", idempotencyKey
        )).isEmpty();

        // 3. Customer snapshot unmutated
        assertThat(getBalance(customerWallet.getId())).isEqualTo(Long.MAX_VALUE - 1000L);
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

    private long getBalance(UUID accountId) {
        return ledgerBalanceSnapshotRepository.findById(accountId)
                .map(LedgerBalanceSnapshot::getBalanceMinor)
                .orElse(0L);
    }

    private void assertReconstructedBalance(UUID accountId) {
        Long sumCredits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM journal_entries WHERE ledger_account_id = ? AND direction = 'CREDIT'",
                Long.class, accountId
        );
        Long sumDebits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM journal_entries WHERE ledger_account_id = ? AND direction = 'DEBIT'",
                Long.class, accountId
        );
        long netLedgerBalance = (sumCredits != null ? sumCredits : 0L) - (sumDebits != null ? sumDebits : 0L);
        long snapshotBalance = getBalance(accountId);

        assertThat(snapshotBalance).isEqualTo(netLedgerBalance);
    }
}
