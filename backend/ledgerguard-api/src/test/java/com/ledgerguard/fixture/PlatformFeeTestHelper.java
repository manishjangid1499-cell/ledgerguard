package com.ledgerguard.fixture;

import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Test-only fixture helper ensuring deterministic singleton semantics
 * for the global PLATFORM_FEES ledger account across all integration tests.
 */
public final class PlatformFeeTestHelper {

    private PlatformFeeTestHelper() {}

    /**
     * Normalizes the active INR PLATFORM_FEES ledger account fixture:
     * - Finds all active ownerless INR PLATFORM_FEES accounts.
     * - If more than one exists, keeps the first one active and closes all subsequent extras.
     * - If none exists, creates exactly one canonical active ownerless INR account.
     *
     * Invariant: Exactly ONE active ownerless INR PLATFORM_FEES ledger account remains.
     */
    public static LedgerAccount ensureSingleActiveFeeAccount(LedgerAccountRepository repository) {
        List<LedgerAccount> feeAccounts = repository.findAllByAccountType(AccountType.PLATFORM_FEES).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE && "INR".equals(a.getCurrency()) && a.getOwnerUserId() == null)
                .sorted(Comparator.comparing(LedgerAccount::getCreatedAt).thenComparing(LedgerAccount::getId))
                .toList();

        if (feeAccounts.isEmpty()) {
            LedgerAccount canonical = LedgerAccount.createSystemAccount(AccountType.PLATFORM_FEES);
            return repository.saveAndFlush(canonical);
        }

        // Close any excess active fee accounts
        for (int i = 1; i < feeAccounts.size(); i++) {
            LedgerAccount extra = feeAccounts.get(i);
            extra.close(Instant.now());
            repository.saveAndFlush(extra);
        }

        return feeAccounts.get(0);
    }

    /**
     * JDBC-based variant for tests interacting with the schema via JdbcTemplate directly.
     */
    public static void ensureSingleActiveFeeAccount(JdbcTemplate jdbcTemplate) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id::text FROM ledger_accounts " +
                "WHERE account_type = 'PLATFORM_FEES' AND currency = 'INR' AND status = 'ACTIVE' AND owner_user_id IS NULL " +
                "ORDER BY created_at ASC, id ASC",
                String.class);

        if (ids.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                    "VALUES (gen_random_uuid(), NULL, 'PLATFORM_FEES', 'INR', 'ACTIVE', NOW(), NOW())");
        } else if (ids.size() > 1) {
            for (int i = 1; i < ids.size(); i++) {
                jdbcTemplate.update(
                        "UPDATE ledger_accounts SET status = 'CLOSED', updated_at = NOW() WHERE id = ?::uuid",
                        ids.get(i));
            }
        }
    }
}
