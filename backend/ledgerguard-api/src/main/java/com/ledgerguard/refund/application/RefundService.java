package com.ledgerguard.refund.application;

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
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.JournalEntry;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.infrastructure.JournalEntryRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.outbox.application.OutboxService;
import com.ledgerguard.outbox.domain.RefundCompletedEvent;
import com.ledgerguard.outbox.domain.RefundCompletedPayload;
import com.ledgerguard.payment.domain.Payment;
import com.ledgerguard.payment.domain.PaymentDestinationNotFoundException;
import com.ledgerguard.payment.domain.PaymentStatus;
import com.ledgerguard.payment.domain.PaymentValidationException;
import com.ledgerguard.payment.domain.PlatformFeeAccountException;
import com.ledgerguard.payment.infrastructure.PaymentRepository;
import com.ledgerguard.refund.domain.PaymentNotRefundableException;
import com.ledgerguard.refund.domain.Refund;
import com.ledgerguard.refund.domain.RefundAllocation;
import com.ledgerguard.refund.domain.RefundAllocationPolicy;
import com.ledgerguard.refund.domain.RefundLimitExceededException;
import com.ledgerguard.refund.infrastructure.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative application service for processing payment refunds.
 * Orchestrates merchant ownership validation, payment status verification, cumulative refund cap enforcement,
 * proportional allocation calculations, deterministic row-locking, double-entry compensating ledger postings,
 * PostgreSQL-backed idempotency, and transactional outbox event persistence.
 */
@Service
public class RefundService {

    public static final String OPERATION_NAMESPACE = "payment-refund:v1";

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerPostingService ledgerPostingService;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;

    public RefundService(
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            LedgerAccountRepository ledgerAccountRepository,
            LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository,
            JournalEntryRepository journalEntryRepository,
            LedgerPostingService ledgerPostingService,
            IdempotencyService idempotencyService,
            OutboxService outboxService
    ) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository must not be null");
        this.refundRepository = Objects.requireNonNull(refundRepository, "refundRepository must not be null");
        this.ledgerAccountRepository = Objects.requireNonNull(ledgerAccountRepository, "ledgerAccountRepository must not be null");
        this.ledgerBalanceSnapshotRepository = Objects.requireNonNull(ledgerBalanceSnapshotRepository, "ledgerBalanceSnapshotRepository must not be null");
        this.journalEntryRepository = Objects.requireNonNull(journalEntryRepository, "journalEntryRepository must not be null");
        this.ledgerPostingService = Objects.requireNonNull(ledgerPostingService, "ledgerPostingService must not be null");
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
        this.outboxService = Objects.requireNonNull(outboxService, "outboxService must not be null");
    }

    /**
     * Executes a full or partial refund for a SUCCEEDED payment within a single PostgreSQL ACID transaction.
     *
     * @param command refund creation parameters
     * @return RefundResult containing refund execution details and replay status
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RefundResult createRefund(CreateRefundCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        long requestedRefundMinor = command.refundAmount().getMinorUnits();
        if (requestedRefundMinor <= 0) {
            throw new PaymentValidationException("Refund amount must be strictly positive");
        }
        if (!"INR".equals(command.refundAmount().getCurrencyCode())) {
            throw new PaymentValidationException("Unsupported currency: " + command.refundAmount().getCurrencyCode());
        }

        // Canonical deterministic request fingerprint
        String canonicalPayload = String.format(
                "%s\npayment=%s\nrefundAmountMinor=%d\ncurrency=INR\nallocationPolicy=%s",
                OPERATION_NAMESPACE,
                command.paymentId(),
                requestedRefundMinor,
                RefundAllocationPolicy.POLICY_VERSION
        );
        RequestFingerprint requestFingerprint = RequestFingerprint.of(canonicalPayload);

        IdempotencyCommand idempotencyCommand = IdempotencyCommand.of(
                command.actorUserId(),
                OPERATION_NAMESPACE,
                command.idempotencyKey(),
                requestFingerprint
        );

        IdempotencyExecutionResult idempotencyExecutionResult = idempotencyService.execute(
                idempotencyCommand,
                () -> {
                    // 1. Lock parent Payment row FOR UPDATE to serialize concurrent refund requests
                    Payment payment = paymentRepository.findByIdForUpdate(command.paymentId())
                            .orElseThrow(() -> new PaymentDestinationNotFoundException(command.paymentId()));

                    // 2. Validate merchant ownership (unrelated merchant receives 404 to avoid disclosure)
                    LedgerAccount merchantWallet = ledgerAccountRepository.findById(payment.getMerchantLedgerAccountId())
                            .orElseThrow(() -> new PaymentDestinationNotFoundException(payment.getMerchantLedgerAccountId()));

                    if (!command.actorUserId().equals(merchantWallet.getOwnerUserId())) {
                        throw new PaymentDestinationNotFoundException(command.paymentId());
                    }

                    // 3. Verify Payment status is SUCCEEDED
                    if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
                        throw new PaymentNotRefundableException("Payment " + payment.getId() + " is in status " + payment.getStatus() + " and cannot be refunded");
                    }

                    // 4. Calculate already refunded gross amount
                    long alreadyRefunded = refundRepository.sumRefundAmountByPaymentId(payment.getId());

                    // 5. Check cumulative refund cap
                    if (alreadyRefunded + requestedRefundMinor > payment.getGrossAmountMinor()) {
                        throw new RefundLimitExceededException("Refund amount exceeds the remaining refundable amount.");
                    }

                    // 6. Calculate proportional allocation
                    RefundAllocation allocation = RefundAllocationPolicy.calculateAllocation(
                            payment.getGrossAmountMinor(),
                            payment.getFeeAmountMinor(),
                            alreadyRefunded,
                            requestedRefundMinor
                    );

                    // 7. Resolve original fee account if fee component > 0
                    UUID originalFeeAccountId = null;
                    if (allocation.feeDebitAmountMinor() > 0) {
                        List<JournalEntry> originalEntries = journalEntryRepository.findByJournalTransactionId(payment.getJournalTransactionId());
                        List<JournalEntry> feeCreditEntries = originalEntries.stream()
                                .filter(e -> e.getDirection() == EntryDirection.CREDIT && e.getAmountMinor() == payment.getFeeAmountMinor())
                                .filter(e -> {
                                    LedgerAccount acc = e.getLedgerAccount();
                                    return acc != null && acc.getAccountType() == AccountType.PLATFORM_FEES;
                                })
                                .toList();

                        if (feeCreditEntries.size() != 1) {
                            throw new PlatformFeeAccountException("Unable to uniquely identify original platform fee account from payment journal " + payment.getJournalTransactionId());
                        }
                        originalFeeAccountId = feeCreditEntries.get(0).getLedgerAccount().getId();
                    }

                    // 8. Determine financial snapshot rows to lock
                    List<UUID> touchedAccountIds = new ArrayList<>();
                    touchedAccountIds.add(payment.getCustomerLedgerAccountId());
                    if (allocation.merchantDebitAmountMinor() > 0) {
                        touchedAccountIds.add(payment.getMerchantLedgerAccountId());
                    }
                    if (allocation.feeDebitAmountMinor() > 0 && originalFeeAccountId != null) {
                        touchedAccountIds.add(originalFeeAccountId);
                    }

                    // 9. Lock financial snapshot rows in deterministic ORDER BY ledger_account_id ASC
                    List<LedgerBalanceSnapshot> lockedSnapshots = ledgerBalanceSnapshotRepository.findAllByLedgerAccountIdInForUpdateOrdered(touchedAccountIds);
                    if (lockedSnapshots.size() != touchedAccountIds.size()) {
                        throw new IllegalStateException("Failed to lock all required balance snapshot rows for refund");
                    }

                    // 10. Post compensating journal
                    List<PostingLine> lines = new ArrayList<>();
                    lines.add(PostingLine.credit(payment.getCustomerLedgerAccountId(), allocation.refundAmountMinor()));
                    if (allocation.merchantDebitAmountMinor() > 0) {
                        lines.add(PostingLine.debit(payment.getMerchantLedgerAccountId(), allocation.merchantDebitAmountMinor()));
                    }
                    if (allocation.feeDebitAmountMinor() > 0 && originalFeeAccountId != null) {
                        lines.add(PostingLine.debit(originalFeeAccountId, allocation.feeDebitAmountMinor()));
                    }

                    PostJournalCommand postCommand = new PostJournalCommand(lines);
                    PostingResult postingResult = ledgerPostingService.post(postCommand);

                    // 11. Persist immutable Refund record referencing POSTED journal
                    UUID refundId = UUID.randomUUID();
                    Refund refund = Refund.create(
                            refundId,
                            payment.getId(),
                            command.actorUserId(),
                            allocation.refundAmountMinor(),
                            allocation.merchantDebitAmountMinor(),
                            allocation.feeDebitAmountMinor(),
                            "INR",
                            postingResult.journalTransactionId(),
                            Instant.now()
                    );
                    refundRepository.saveAndFlush(refund);

                    // 12. Append REFUND_COMPLETED domain event to transactional outbox
                    outboxService.append(RefundCompletedEvent.of(
                            UUID.randomUUID(),
                            refund.getId(),
                            refund.getCreatedAt(),
                            new RefundCompletedPayload(
                                    refund.getId().toString(),
                                    refund.getPaymentId().toString(),
                                    String.valueOf(refund.getRefundAmountMinor()),
                                    String.valueOf(refund.getMerchantDebitAmountMinor()),
                                    String.valueOf(refund.getFeeDebitAmountMinor()),
                                    refund.getCurrency(),
                                    postingResult.journalTransactionId().toString()
                            )
                    ));

                    return refundId;
                }
        );

        Refund refund = refundRepository.findById(idempotencyExecutionResult.resultId())
                .orElseThrow(() -> new IllegalStateException("Committed refund record not found: " + idempotencyExecutionResult.resultId()));

        return new RefundResult(
                refund.getId(),
                refund.getPaymentId(),
                refund.getRefundAmountMinor(),
                refund.getMerchantDebitAmountMinor(),
                refund.getFeeDebitAmountMinor(),
                refund.getCurrency(),
                refund.getJournalTransactionId(),
                refund.getCreatedAt(),
                idempotencyExecutionResult.replayed()
        );
    }
}
