package com.ledgerguard.ledger.application;

import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service for provisioning user wallets (owned ledger accounts).
 */
@Service
public class WalletProvisioningService {

    private final LedgerAccountRepository ledgerAccountRepository;

    public WalletProvisioningService(LedgerAccountRepository ledgerAccountRepository) {
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    /**
     * Provisions a wallet (owned ledger account) for a CUSTOMER or MERCHANT user.
     * Rejects OPS users.
     *
     * @param userId owner user ID
     * @param role user role (CUSTOMER or MERCHANT)
     * @return provisioned LedgerAccount
     */
    @Transactional
    public LedgerAccount provisionWallet(UUID userId, UserRole role) {
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(role, "User role must not be null");

        if (role == UserRole.OPS) {
            throw new IllegalArgumentException("OPS users cannot be provisioned with a wallet");
        }

        List<LedgerAccount> existing = ledgerAccountRepository.findByOwnerUserId(userId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        LedgerAccount account;
        if (role == UserRole.CUSTOMER) {
            account = LedgerAccount.createCustomerAccount(userId);
        } else if (role == UserRole.MERCHANT) {
            account = LedgerAccount.createMerchantAccount(userId);
        } else {
            throw new IllegalArgumentException("Unsupported user role for wallet provisioning: " + role);
        }

        return ledgerAccountRepository.saveAndFlush(account);
    }
}
