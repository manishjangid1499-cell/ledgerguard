package com.ledgerguard.lab.invariants;

import com.ledgerguard.lab.model.InvariantCheckResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Independent financial invariant oracle that executes direct SQL queries
 * against PostgreSQL to verify core financial integrity and double-entry invariants.
 */
public class FinancialInvariantOracle {

    /**
     * Asserts that every POSTED journal transaction is structurally valid:
     * - Has at least 2 journal entries
     * - Contains at least one DEBIT entry
     * - Contains at least one CREDIT entry
     * - All entry amounts are strictly positive (> 0)
     */
    public List<InvariantCheckResult> checkJournalStructure(Connection conn) throws SQLException {
        List<InvariantCheckResult> results = new ArrayList<>();

        // Check for empty or malformed POSTED transactions using LEFT JOIN to expose zero-entry journals
        String sql = """
                SELECT jt.id,
                       count(je.id) AS entry_count,
                       count(CASE WHEN je.direction = 'DEBIT' THEN 1 END) AS debit_count,
                       count(CASE WHEN je.direction = 'CREDIT' THEN 1 END) AS credit_count,
                       min(je.amount_minor) AS min_amount
                FROM journal_transactions jt
                LEFT JOIN journal_entries je ON je.journal_transaction_id = jt.id
                WHERE jt.status = 'POSTED'
                GROUP BY jt.id
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean anyTransactions = false;
            while (rs.next()) {
                anyTransactions = true;
                UUID txId = (UUID) rs.getObject("id");
                long entryCount = rs.getLong("entry_count");
                long debitCount = rs.getLong("debit_count");
                long creditCount = rs.getLong("credit_count");
                long minAmount = rs.getLong("min_amount");

                if (entryCount < 2) {
                    results.add(InvariantCheckResult.fail(
                            "JOURNAL_MIN_ENTRIES",
                            ">= 2 entries",
                            entryCount,
                            "Transaction " + txId + " has fewer than 2 entries: " + entryCount
                    ));
                }
                if (debitCount < 1) {
                    results.add(InvariantCheckResult.fail(
                            "JOURNAL_HAS_DEBIT",
                            ">= 1 debit",
                            debitCount,
                            "Transaction " + txId + " has no DEBIT entry"
                    ));
                }
                if (creditCount < 1) {
                    results.add(InvariantCheckResult.fail(
                            "JOURNAL_HAS_CREDIT",
                            ">= 1 credit",
                            creditCount,
                            "Transaction " + txId + " has no CREDIT entry"
                    ));
                }
                if (entryCount > 0 && minAmount <= 0) {
                    results.add(InvariantCheckResult.fail(
                            "JOURNAL_POSITIVE_AMOUNTS",
                            "> 0 minor units",
                            minAmount,
                            "Transaction " + txId + " contains non-positive entry amount: " + minAmount
                    ));
                }
            }

            if (!anyTransactions) {
                results.add(InvariantCheckResult.pass("JOURNAL_STRUCTURE", "No POSTED transactions present (empty ledger)"));
            } else if (results.isEmpty()) {
                results.add(InvariantCheckResult.pass("JOURNAL_STRUCTURE", "All POSTED journal transactions are structurally sound"));
            }
        }

        return results;
    }

    /**
     * Asserts debit/credit equality:
     * 1. Per transaction: sum(DEBIT) == sum(CREDIT) for each POSTED transaction.
     * 2. Global: sum(all DEBITS) == sum(all CREDITS) across all POSTED transactions.
     */
    public List<InvariantCheckResult> checkDebitCreditEquality(Connection conn) throws SQLException {
        List<InvariantCheckResult> results = new ArrayList<>();

        // Per-transaction balance check
        String perTxSql = """
                SELECT jt.id,
                       coalesce(sum(CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor ELSE 0 END), 0) AS sum_debit,
                       coalesce(sum(CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor ELSE 0 END), 0) AS sum_credit
                FROM journal_transactions jt
                LEFT JOIN journal_entries je ON je.journal_transaction_id = jt.id
                WHERE jt.status = 'POSTED'
                GROUP BY jt.id
                """;

        long totalDebits = 0;
        long totalCredits = 0;
        boolean allTransactionsBalanced = true;

        try (PreparedStatement ps = conn.prepareStatement(perTxSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID txId = (UUID) rs.getObject("id");
                long debits = rs.getLong("sum_debit");
                long credits = rs.getLong("sum_credit");
                totalDebits += debits;
                totalCredits += credits;

                if (debits != credits) {
                    allTransactionsBalanced = false;
                    results.add(InvariantCheckResult.fail(
                            "TRANSACTION_ZERO_SUM",
                            debits,
                            credits,
                            "Transaction " + txId + " is unbalanced: Debits=" + debits + ", Credits=" + credits
                    ));
                }
            }
        }

        if (allTransactionsBalanced) {
            results.add(InvariantCheckResult.pass("TRANSACTION_ZERO_SUM", totalDebits, totalCredits, "All individual transactions balance to zero"));
        }

        // Global debit == credit check
        if (totalDebits == totalCredits) {
            results.add(InvariantCheckResult.pass("GLOBAL_ZERO_SUM", totalDebits, totalCredits, "Global sum of debits matches credits (" + totalDebits + " minor units)"));
        } else {
            results.add(InvariantCheckResult.fail("GLOBAL_ZERO_SUM", totalDebits, totalCredits, "Global ledger imbalance! Total Debits=" + totalDebits + ", Total Credits=" + totalCredits));
        }

        return results;
    }

    /**
     * Asserts snapshot consistency:
     * Compares stored balance_minor in ledger_balance_snapshots against authoritative
     * reconstruction from immutable POSTED journal entries according to account normal-balance rules.
     */
    public List<InvariantCheckResult> checkSnapshotIntegrity(Connection conn, UUID... accountIds) throws SQLException {
        List<InvariantCheckResult> results = new ArrayList<>();

        String whereClause = "";
        if (accountIds != null && accountIds.length > 0) {
            String placeholders = String.join(",", java.util.Collections.nCopies(accountIds.length, "?::uuid"));
            whereClause = "WHERE la.id IN (" + placeholders + ") ";
        }

        String sql = "SELECT la.id AS account_id, " +
                "       la.account_type, " +
                "       coalesce(lbs.balance_minor, 0) AS stored_snapshot, " +
                "       coalesce( " +
                "           sum( " +
                "               CASE " +
                "                   WHEN jt.status = 'POSTED' THEN " +
                "                       CASE " +
                "                           WHEN la.account_type IN ('CUSTOMER', 'MERCHANT', 'PLATFORM_FEES') THEN " +
                "                               CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor ELSE -je.amount_minor END " +
                "                           ELSE " +
                "                               CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor ELSE -je.amount_minor END " +
                "                       END " +
                "                   ELSE 0 " +
                "               END " +
                "           ), 0 " +
                "       ) AS reconstructed_balance " +
                "FROM ledger_accounts la " +
                "LEFT JOIN ledger_balance_snapshots lbs ON lbs.ledger_account_id = la.id " +
                "LEFT JOIN journal_entries je ON je.ledger_account_id = la.id " +
                "LEFT JOIN journal_transactions jt ON jt.id = je.journal_transaction_id " +
                whereClause +
                "GROUP BY la.id, la.account_type, lbs.balance_minor";

        boolean allSnapshotsValid = true;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (accountIds != null && accountIds.length > 0) {
                for (int i = 0; i < accountIds.length; i++) {
                    ps.setObject(i + 1, accountIds[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID accountId = (UUID) rs.getObject("account_id");
                    String accountType = rs.getString("account_type");
                    long storedSnapshot = rs.getLong("stored_snapshot");
                    long reconstructed = rs.getLong("reconstructed_balance");

                    if (storedSnapshot != reconstructed) {
                        allSnapshotsValid = false;
                        results.add(InvariantCheckResult.fail(
                                "SNAPSHOT_EQUALS_RECONSTRUCTION",
                                reconstructed,
                                storedSnapshot,
                                "Account " + accountId + " (" + accountType + ") snapshot mismatch! Stored=" + storedSnapshot + ", Reconstructed=" + reconstructed
                        ));
                    }
                }
            }
        }

        if (allSnapshotsValid) {
            results.add(InvariantCheckResult.pass("SNAPSHOT_EQUALS_RECONSTRUCTION", "All account balance snapshots match journal reconstruction exactly"));
        }

        return results;
    }

    /**
     * Asserts available balance invariant for spendable customer / merchant accounts:
     * available_balance = posted_balance - sum(active_holds).
     * For customer and merchant accounts, verifies available balance is not negative (>= 0).
     */
    public InvariantCheckResult checkAvailableBalance(Connection conn, UUID accountId) throws SQLException {
        String sql = """
                SELECT la.account_type,
                       coalesce(lbs.balance_minor, 0) AS posted_balance,
                       coalesce(sum(CASE WHEN bh.status = 'ACTIVE' THEN bh.amount_minor ELSE 0 END), 0) AS active_holds
                FROM ledger_accounts la
                LEFT JOIN ledger_balance_snapshots lbs ON lbs.ledger_account_id = la.id
                LEFT JOIN balance_holds bh ON bh.ledger_account_id = la.id
                WHERE la.id = ?
                GROUP BY la.account_type, lbs.balance_minor
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String accountType = rs.getString("account_type");
                    long posted = rs.getLong("posted_balance");
                    long activeHolds = rs.getLong("active_holds");
                    long available = posted - activeHolds;

                    // Non-negative constraint strictly applies to CUSTOMER and MERCHANT accounts
                    if (("CUSTOMER".equals(accountType) || "MERCHANT".equals(accountType)) && available < 0) {
                        return InvariantCheckResult.fail(
                                "AVAILABLE_BALANCE_NON_NEGATIVE",
                                ">= 0",
                                available,
                                "Account " + accountId + " (" + accountType + ") has negative available balance: posted="
                                        + posted + ", holds=" + activeHolds + ", available=" + available
                        );
                    }
                    return InvariantCheckResult.pass(
                            "AVAILABLE_BALANCE_VALID",
                            posted - activeHolds,
                            available,
                            "Account " + accountId + " (" + accountType + ") available balance valid: posted="
                                    + posted + ", activeHolds=" + activeHolds + ", available=" + available
                    );
                }
                return InvariantCheckResult.fail("AVAILABLE_BALANCE_EXISTS", "Account exists", "Not found", "Account " + accountId + " not found");
            }
        }
    }

    /**
     * Asserts internal transfer money conservation across two participating accounts:
     * The sum of balances across account A and account B equals the initial total money.
     * delta(A) + delta(B) = 0.
     */
    public InvariantCheckResult checkInternalTransferConservation(
            Connection conn,
            UUID accountA,
            UUID accountB,
            long expectedTotalMinor
    ) throws SQLException {
        String sql = "SELECT coalesce(sum(balance_minor), 0) AS total_balance FROM ledger_balance_snapshots WHERE ledger_account_id IN (?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, accountA);
            ps.setObject(2, accountB);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long total = rs.getLong("total_balance");
                    if (total == expectedTotalMinor) {
                        return InvariantCheckResult.pass(
                                "INTERNAL_TRANSFER_CONSERVATION",
                                expectedTotalMinor,
                                total,
                                "Money conserved across accounts " + accountA + " and " + accountB + ": exact sum = " + total + " minor units"
                        );
                    } else {
                        return InvariantCheckResult.fail(
                                "INTERNAL_TRANSFER_CONSERVATION",
                                expectedTotalMinor,
                                total,
                                "Conservation violation! Expected " + expectedTotalMinor + " minor units, found " + total
                        );
                    }
                }
            }
        }
        return InvariantCheckResult.fail("INTERNAL_TRANSFER_CONSERVATION", expectedTotalMinor, "N/A", "Failed to query account balances");
    }

    /**
     * Asserts that a business transaction produced EXACTLY the expected number of POSTED settlement journals.
     *
     * @param conn database connection
     * @param journalTxnId journal transaction ID
     * @param expectedCount expected count of POSTED journals (e.g. 1 for completed settlement, 0 for in-flight/failed)
     */
    public InvariantCheckResult checkSettlementJournalCount(Connection conn, UUID journalTxnId, int expectedCount) throws SQLException {
        if (journalTxnId == null) {
            if (expectedCount == 0) {
                return InvariantCheckResult.pass("SETTLEMENT_JOURNAL_COUNT", 0, 0, "No settlement journal posted (expected 0)");
            } else {
                return InvariantCheckResult.fail("SETTLEMENT_JOURNAL_COUNT", expectedCount, 0, "Journal transaction ID is null but expected " + expectedCount);
            }
        }

        String sql = "SELECT count(*) FROM journal_transactions WHERE id = ? AND status = 'POSTED'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, journalTxnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    if (count == expectedCount) {
                        return InvariantCheckResult.pass("SETTLEMENT_JOURNAL_COUNT", expectedCount, count,
                                "Settlement journal " + journalTxnId + " count matches expected: " + count);
                    } else {
                        return InvariantCheckResult.fail("SETTLEMENT_JOURNAL_COUNT", expectedCount, count,
                                "Settlement journal " + journalTxnId + " count mismatch: found " + count + ", expected " + expectedCount);
                    }
                }
            }
        }
        return InvariantCheckResult.fail("SETTLEMENT_JOURNAL_COUNT", expectedCount, 0, "Failed to query journal transaction");
    }
}
