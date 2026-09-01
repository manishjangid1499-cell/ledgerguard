package com.ledgerguard.payment.application;

import com.ledgerguard.hold.domain.AvailableBalance;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.idempotency.application.IdempotencyCommand;
import com.ledgerguard.idempotency.application.IdempotencyExecutionResult;
import com.ledgerguard.idempotency.application.IdempotencyService;
import com.ledgerguard.idempotency.domain.RequestFingerprint;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.application.PostingResult;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.payment.domain.FeeCalculation;
import com.ledgerguard.payment.domain.Payment;
import com.ledgerguard.payment.domain.PaymentDestinationNotFoundException;
import com.ledgerguard.payment.domain.PaymentValidationException;
import com.ledgerguard.payment.domain.PlatformFeeAccountException;
import com.ledgerguard.payment.domain.PlatformFeePolicy;
import com.ledgerguard.payment.infrastructure.PaymentRepository;
import com.ledgerguard.transfer.domain.InsufficientFundsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative application service for orchestrating internal merchant payments.
 * <p>
 * Combines customer identity derivation, merchant account validation, platform fee calculation,
 * idempotency coordination, deterministic snapshot row locking, atomic sufficient-funds validation,
 * double-entry ledger posting, and immutable payment business record persistence in a single
 * atomic database transaction.
 */
@Service
public class PaymentService {

    public static final String OPERATION_NAMESPACE = "merchant-payment:v1";

    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;
    private final BalanceHoldRepository balanceHoldRepository;
    private final LedgerPostingService ledgerPostingService;
    private final IdempotencyService idempotencyService;
    private final PaymentRepository paymentRepository;

    public PaymentService(
            LedgerAccountRepository ledgerAccountRepository,
            LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository,
            BalanceHoldRepository balanceHoldRepository,
            LedgerPostingService ledgerPostingService,
            IdempotencyService idempotencyService,
            PaymentRepository paymentRepository
    ) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerBalanceSnapshotRepository = ledgerBalanceSnapshotRepository;
        this.balanceHoldRepository = balanceHoldRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.idempotencyService = idempotencyService;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Executes an internal merchant payment idempotently, deterministically, and atomically.
     *
     * @param command validated create payment command
     * @return PaymentResult containing payment details and replay indicator
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentResult createPayment(CreatePaymentCommand command) {
        Objects.requireNonNull(command, "CreatePaymentCommand must not be null");

        long grossAmountMinor = command.amount().getMinorUnits();

        // 1. Amount validation
        if (grossAmountMinor <= 0) {
            throw new PaymentValidationException("Payment amount must be strictly positive: " + grossAmountMinor);
        }
        if (!"INR".equals(command.amount().getCurrencyCode())) {
            throw new PaymentValidationException("Payment currency must be INR: " + command.amount().getCurrencyCode());
        }

        // 2. Resolve and validate customer wallet from authenticated actor
        List<LedgerAccount> customerAccounts = ledgerAccountRepository.findByOwnerUserId(command.actorUserId());
        if (customerAccounts.isEmpty()) {
            throw new PaymentValidationException("No wallet account found for authenticated customer: " + command.actorUserId());
        }
        LedgerAccount customerAccount = customerAccounts.get(0);

        if (customerAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new PaymentValidationException("Customer wallet account is not active: " + customerAccount.getId() + " (status: " + customerAccount.getStatus() + ")");
        }
        if (customerAccount.getAccountType() != AccountType.CUSTOMER) {
            throw new PaymentValidationException("Payer account must be a CUSTOMER wallet: " + customerAccount.getId() + " (found: " + customerAccount.getAccountType() + ")");
        }
        if (!"INR".equals(customerAccount.getCurrency())) {
            throw new PaymentValidationException("Customer wallet currency must be INR: " + customerAccount.getCurrency());
        }

        // 3. Resolve and validate merchant wallet
        LedgerAccount merchantAccount = ledgerAccountRepository.findById(command.merchantLedgerAccountId())
                .orElseThrow(() -> new PaymentDestinationNotFoundException(command.merchantLedgerAccountId()));

        if (merchantAccount.getOwnerUserId() == null) {
            throw new PaymentValidationException("Payments to system ledger accounts are not permitted: " + merchantAccount.getId());
        }
        if (merchantAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new PaymentValidationException("Merchant wallet account is not active: " + merchantAccount.getId() + " (status: " + merchantAccount.getStatus() + ")");
        }
        if (merchantAccount.getAccountType() != AccountType.MERCHANT) {
            throw new PaymentValidationException("Destination account must be a MERCHANT wallet: " + merchantAccount.getId() + " (found: " + merchantAccount.getAccountType() + ")");
        }
        if (!"INR".equals(merchantAccount.getCurrency())) {
            throw new PaymentValidationException("Merchant wallet currency must be INR: " + merchantAccount.getCurrency());
        }

        // 4. Self-payment validation
        if (customerAccount.getId().equals(merchantAccount.getId())) {
            throw new PaymentValidationException("Self-payments to the same ledger account are not permitted: " + customerAccount.getId());
        }

        // 5. Calculate platform fee and merchant net
        FeeCalculation feeCalculation = PlatformFeePolicy.calculateFee(grossAmountMinor);

        // 6. Deterministic canonical request fingerprint
        String canonicalPayload = String.format(
                "%s\ncustomer=%s\nmerchant=%s\ngrossAmountMinor=%d\ncurrency=%s\nfeeBasisPoints=%d",
                OPERATION_NAMESPACE,
                customerAccount.getId(),
                merchantAccount.getId(),
                grossAmountMinor,
                command.amount().getCurrencyCode(),
                feeCalculation.feeBasisPoints()
        );
        RequestFingerprint fingerprint = RequestFingerprint.of(canonicalPayload);

        // 7. Idempotency coordination & atomic execution
        IdempotencyCommand idempotencyCommand = IdempotencyCommand.of(
                command.actorUserId(),
                OPERATION_NAMESPACE,
                command.idempotencyKey(),
                fingerprint
        );

        IdempotencyExecutionResult executionResult = idempotencyService.execute(idempotencyCommand, () -> {
            LedgerAccount platformFeeAccount = null;
            List<UUID> accountsToLock;

            if (feeCalculation.feeAmountMinor() > 0) {
                // Resolve system PLATFORM_FEES account
                List<LedgerAccount> feeAccounts = ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_FEES).stream()
                        .filter(a -> a.getStatus() == AccountStatus.ACTIVE && "INR".equals(a.getCurrency()) && a.getOwnerUserId() == null)
                        .toList();

                if (feeAccounts.isEmpty()) {
                    throw new PlatformFeeAccountException("No active INR PLATFORM_FEES ledger account configured");
                }
                if (feeAccounts.size() > 1) {
                    throw new PlatformFeeAccountException("Multiple active INR PLATFORM_FEES ledger accounts found: " + feeAccounts.size());
                }
                platformFeeAccount = feeAccounts.get(0);

                accountsToLock = List.of(customerAccount.getId(), merchantAccount.getId(), platformFeeAccount.getId());
            } else {
                accountsToLock = List.of(customerAccount.getId(), merchantAccount.getId());
            }

            // A. Acquire deterministic pessimistic row locks on all touched balance snapshots (ORDER BY ledger_account_id ASC)
            List<LedgerBalanceSnapshot> lockedSnapshots = ledgerBalanceSnapshotRepository.findAllByLedgerAccountIdInForUpdateOrdered(accountsToLock);
            if (lockedSnapshots.size() != accountsToLock.size()) {
                throw new IllegalStateException("Failed to lock all required balance snapshot rows. Expected "
                        + accountsToLock.size() + ", found: " + lockedSnapshots.size());
            }

            // B. Read locked customer balance, compute exact AvailableBalance, and validate available funds against GROSS amount
            LedgerBalanceSnapshot customerSnapshot = lockedSnapshots.stream()
                    .filter(s -> s.getLedgerAccountId().equals(customerAccount.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Customer balance snapshot not found"));

            long customerActiveHolds = balanceHoldRepository.sumActiveAmountByLedgerAccountId(customerAccount.getId());
            AvailableBalance customerAvailable = AvailableBalance.of(customerSnapshot.getBalanceMinor(), customerActiveHolds);

            if (!customerAvailable.hasAvailable(grossAmountMinor)) {
                throw new InsufficientFundsException("Insufficient funds for this payment.");
            }

            // C. Create Payment in CREATED state and flush to trigger DB lifecycle validation
            UUID paymentId = UUID.randomUUID();
            Instant now = Instant.now();
            Payment payment = Payment.create(
                    paymentId,
                    command.actorUserId(),
                    customerAccount.getId(),
                    merchantAccount.getId(),
                    grossAmountMinor,
                    feeCalculation.feeAmountMinor(),
                    feeCalculation.merchantNetAmountMinor(),
                    command.amount().getCurrencyCode(),
                    now
            );
            paymentRepository.saveAndFlush(payment);

            // D. Transition to PROCESSING state and flush
            payment.markProcessing(Instant.now());
            paymentRepository.saveAndFlush(payment);

            // E. Post balanced double-entry journal transaction
            PostJournalCommand postJournalCommand;
            if (feeCalculation.feeAmountMinor() > 0) {
                postJournalCommand = PostJournalCommand.of(
                        PostingLine.debit(customerAccount.getId(), grossAmountMinor),
                        PostingLine.credit(merchantAccount.getId(), feeCalculation.merchantNetAmountMinor()),
                        PostingLine.credit(platformFeeAccount.getId(), feeCalculation.feeAmountMinor())
                );
            } else {
                postJournalCommand = PostJournalCommand.of(
                        PostingLine.debit(customerAccount.getId(), grossAmountMinor),
                        PostingLine.credit(merchantAccount.getId(), grossAmountMinor)
                );
            }
            PostingResult postingResult = ledgerPostingService.post(postJournalCommand);

            // F. Transition to SUCCEEDED state with posted journal reference and flush
            payment.markSucceeded(postingResult.journalTransactionId(), Instant.now());
            paymentRepository.saveAndFlush(payment);

            return paymentId;
        });

        // 8. Retrieve completed payment record
        Payment payment = paymentRepository.findById(executionResult.resultId())
                .orElseThrow(() -> new IllegalStateException("Payment record not found: " + executionResult.resultId()));

        return new PaymentResult(
                payment.getId(),
                payment.getCustomerLedgerAccountId(),
                payment.getMerchantLedgerAccountId(),
                payment.getGrossAmountMinor(),
                payment.getFeeAmountMinor(),
                payment.getMerchantNetAmountMinor(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getJournalTransactionId(),
                payment.getCreatedAt(),
                payment.getCompletedAt(),
                executionResult.replayed()
        );
    }
}
