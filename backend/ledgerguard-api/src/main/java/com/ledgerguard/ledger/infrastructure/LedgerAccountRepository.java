package com.ledgerguard.ledger.infrastructure;

import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    List<LedgerAccount> findByOwnerUserId(UUID ownerUserId);

    Optional<LedgerAccount> findByAccountType(AccountType accountType);

    List<LedgerAccount> findAllByAccountType(AccountType accountType);
}
