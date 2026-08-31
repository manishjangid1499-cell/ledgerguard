package com.ledgerguard.ledger.application;

import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.domain.Wallet;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only query service for resolving user wallets with fast derived balance snapshots.
 */
@Service
public class WalletQueryService {

    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    public WalletQueryService(
            LedgerAccountRepository ledgerAccountRepository,
            LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository
    ) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerBalanceSnapshotRepository = ledgerBalanceSnapshotRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Wallet> findWalletByUserId(UUID userId) {
        Objects.requireNonNull(userId, "User ID must not be null");
        List<LedgerAccount> accounts = ledgerAccountRepository.findByOwnerUserId(userId);
        if (accounts.isEmpty()) {
            return Optional.empty();
        }
        LedgerAccount account = accounts.get(0);
        return findWalletByAccount(account);
    }

    @Transactional(readOnly = true)
    public Optional<Wallet> findWalletByAccountId(UUID accountId) {
        Objects.requireNonNull(accountId, "Account ID must not be null");
        return ledgerAccountRepository.findById(accountId)
                .flatMap(this::findWalletByAccount);
    }

    private Optional<Wallet> findWalletByAccount(LedgerAccount account) {
        if (account.getOwnerUserId() == null) {
            return Optional.empty(); // System accounts are not user wallets
        }
        return ledgerBalanceSnapshotRepository.findById(account.getId())
                .map(snapshot -> new Wallet(
                        account.getId(),
                        account.getOwnerUserId(),
                        account.getAccountType(),
                        account.getCurrency(),
                        account.getStatus(),
                        Money.inr(snapshot.getBalanceMinor())
                ));
    }
}
