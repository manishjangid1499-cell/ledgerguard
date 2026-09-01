package com.ledgerguard.payout.application;

import com.ledgerguard.hold.application.HoldService;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.idempotency.application.IdempotencyCommand;
import com.ledgerguard.idempotency.application.IdempotencyExecutionResult;
import com.ledgerguard.idempotency.application.IdempotencyService;
import com.ledgerguard.idempotency.domain.RequestFingerprint;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutValidationException;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transactional boundary for durable creation and idempotency registration of a Payout.
 * <p>
 * Ensures that the initial PROCESSING payout record and its linked active BalanceHold are
 * committed to PostgreSQL before any external network call to the PSP simulator is made.
 */
@Service
public class PayoutCreationService {

    private static final Logger log = LoggerFactory.getLogger(PayoutCreationService.class);
    public static final String OPERATION_NAMESPACE = "external-payout:v1";
    public static final Duration DEFAULT_HOLD_DURATION = Duration.ofMinutes(30);

    private final PayoutRepository payoutRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final HoldService holdService;
    private final IdempotencyService idempotencyService;

    public PayoutCreationService(
            PayoutRepository payoutRepository,
            LedgerAccountRepository ledgerAccountRepository,
            HoldService holdService,
            IdempotencyService idempotencyService
    ) {
        this.payoutRepository = payoutRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.holdService = holdService;
        this.idempotencyService = idempotencyService;
    }

    public record PayoutCreationResult(Payout payout, boolean replayed) {}

    @Transactional(propagation = Propagation.REQUIRED)
    public PayoutCreationResult createOrGetProcessingPayout(CreatePayoutCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // 1. Resolve source wallet account (CUSTOMER or MERCHANT)
        List<LedgerAccount> accounts = ledgerAccountRepository.findByOwnerUserId(command.actorUserId());
        List<LedgerAccount> validWallets = accounts.stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE)
                .filter(a -> "INR".equals(a.getCurrency()))
                .filter(a -> a.getAccountType() == AccountType.CUSTOMER || a.getAccountType() == AccountType.MERCHANT)
                .toList();

        if (validWallets.isEmpty()) {
            throw new PayoutValidationException("No active INR CUSTOMER or MERCHANT wallet found for user: " + command.actorUserId());
        }
        if (validWallets.size() > 1) {
            throw new PayoutValidationException("Multiple active wallets found for user: " + command.actorUserId());
        }
        LedgerAccount sourceWallet = validWallets.get(0);

        // 2. Canonical request fingerprint
        String canonicalPayload = String.format(
                "%s\nactor=%s\nsourceAccount=%s\namountMinor=%d\ncurrency=%s",
                OPERATION_NAMESPACE,
                command.actorUserId(),
                sourceWallet.getId(),
                command.amount().getMinorUnits(),
                command.amount().getCurrencyCode()
        );
        RequestFingerprint fingerprint = RequestFingerprint.of(canonicalPayload);

        IdempotencyCommand idempotencyCommand = IdempotencyCommand.of(
                command.actorUserId(),
                OPERATION_NAMESPACE,
                command.idempotencyKey(),
                fingerprint
        );

        IdempotencyExecutionResult execResult = idempotencyService.execute(idempotencyCommand, () -> {
            UUID payoutId = UUID.randomUUID();
            Instant now = Instant.now();
            Instant expiresAt = now.plus(DEFAULT_HOLD_DURATION);

            // Create BalanceHold with concurrency capacity check
            BalanceHold hold = holdService.createHold(sourceWallet.getId(), command.amount(), expiresAt);

            // Create durable Payout in PROCESSING status
            Payout payout = new Payout(
                    payoutId,
                    command.actorUserId(),
                    sourceWallet.getId(),
                    hold.getId(),
                    command.amount().getMinorUnits(),
                    command.amount().getCurrencyCode(),
                    now
            );
            payoutRepository.saveAndFlush(payout);

            log.info("Created durable PROCESSING Payout: id={}, holdId={}, actor={}, amount={}",
                    payoutId, hold.getId(), command.actorUserId(), command.amount().getMinorUnits());

            return payoutId;
        });

        Payout payout = payoutRepository.findById(execResult.resultId())
                .orElseThrow(() -> new IllegalStateException("Payout not found for ID: " + execResult.resultId()));

        return new PayoutCreationResult(payout, execResult.replayed());
    }
}
