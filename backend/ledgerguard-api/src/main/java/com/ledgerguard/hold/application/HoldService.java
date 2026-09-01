package com.ledgerguard.hold.application;

import com.ledgerguard.hold.domain.AvailableBalance;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldNotFoundException;
import com.ledgerguard.hold.domain.HoldValidationException;
import com.ledgerguard.hold.domain.InsufficientAvailableBalanceException;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.LedgerBalanceSnapshot;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service orchestrating balance hold reservations and lifecycle transitions.
 * Coordinates hold creation via deterministic snapshot row locking.
 */
@Service
public class HoldService {

    private final BalanceHoldRepository balanceHoldRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    public HoldService(
            BalanceHoldRepository balanceHoldRepository,
            LedgerAccountRepository ledgerAccountRepository,
            LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository
    ) {
        this.balanceHoldRepository = balanceHoldRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerBalanceSnapshotRepository = ledgerBalanceSnapshotRepository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public BalanceHold createHold(UUID ledgerAccountId, Money amount, Instant expiresAt) {
        Objects.requireNonNull(ledgerAccountId, "Ledger account ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(expiresAt, "Expires at must not be null");

        if (amount.getMinorUnits() <= 0) {
            throw new HoldValidationException("Hold amount must be strictly positive: " + amount.getMinorUnits());
        }
        if (!"INR".equals(amount.getCurrencyCode())) {
            throw new HoldValidationException("Hold currency must be INR: " + amount.getCurrencyCode());
        }

        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) {
            throw new HoldValidationException("Expires at must be strictly in the future");
        }

        LedgerAccount account = ledgerAccountRepository.findById(ledgerAccountId)
                .orElseThrow(() -> new HoldValidationException("Ledger account not found: " + ledgerAccountId));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new HoldValidationException("Ledger account is not active: " + account.getStatus());
        }
        if (account.getAccountType() != AccountType.CUSTOMER && account.getAccountType() != AccountType.MERCHANT) {
            throw new HoldValidationException("Balance holds are only permitted for user wallet accounts (CUSTOMER or MERCHANT): " + account.getAccountType());
        }
        if (account.getOwnerUserId() == null) {
            throw new HoldValidationException("Cannot create balance hold for system account without owner: " + ledgerAccountId);
        }
        if (!"INR".equals(account.getCurrency())) {
            throw new HoldValidationException("Ledger account currency must be INR: " + account.getCurrency());
        }

        // 1. Acquire deterministic pessimistic write lock on the wallet's balance snapshot row
        List<LedgerBalanceSnapshot> lockedSnapshots = ledgerBalanceSnapshotRepository.findAllByLedgerAccountIdInForUpdateOrdered(List.of(ledgerAccountId));
        if (lockedSnapshots.isEmpty()) {
            throw new IllegalStateException("Balance snapshot missing for ledger account: " + ledgerAccountId);
        }
        LedgerBalanceSnapshot snapshot = lockedSnapshots.get(0);

        // 2. Query sum of active holds for the account under lock
        long activeHoldAmount = balanceHoldRepository.sumActiveAmountByLedgerAccountId(ledgerAccountId);

        // 3. Compute available balance and check capacity using BigInteger exact arithmetic
        AvailableBalance availableBalance = AvailableBalance.of(snapshot.getBalanceMinor(), activeHoldAmount);
        if (!availableBalance.hasAvailable(amount.getMinorUnits())) {
            throw new InsufficientAvailableBalanceException(
                    String.format("Insufficient available balance for hold: requested %d, available %s (posted: %d, held: %d)",
                            amount.getMinorUnits(), availableBalance.availableBalanceMinorString(), snapshot.getBalanceMinor(), activeHoldAmount)
            );
        }

        // 4. Persist ACTIVE hold
        BalanceHold hold = BalanceHold.create(
                UUID.randomUUID(),
                ledgerAccountId,
                amount.getMinorUnits(),
                amount.getCurrencyCode(),
                expiresAt,
                now
        );

        return balanceHoldRepository.saveAndFlush(hold);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public BalanceHold releaseHold(UUID holdId) {
        Objects.requireNonNull(holdId, "Hold ID must not be null");
        BalanceHold hold = balanceHoldRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        hold.release(Instant.now());
        return balanceHoldRepository.saveAndFlush(hold);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public BalanceHold consumeHold(UUID holdId) {
        Objects.requireNonNull(holdId, "Hold ID must not be null");
        BalanceHold hold = balanceHoldRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        hold.consume(Instant.now());
        return balanceHoldRepository.saveAndFlush(hold);
    }

    @Transactional(readOnly = true)
    public AvailableBalance getAvailableBalance(UUID ledgerAccountId) {
        Objects.requireNonNull(ledgerAccountId, "Ledger account ID must not be null");
        long postedBalance = ledgerBalanceSnapshotRepository.findById(ledgerAccountId)
                .map(LedgerBalanceSnapshot::getBalanceMinor)
                .orElse(0L);
        long activeHolds = balanceHoldRepository.sumActiveAmountByLedgerAccountId(ledgerAccountId);
        return AvailableBalance.of(postedBalance, activeHolds);
    }
}
