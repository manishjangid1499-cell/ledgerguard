package com.ledgerguard.reconciliation.application;

import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Level 2 reconciliation: Snapshot Consistency.
 * <p>
 * Reconstructs the expected posted balance for every ledger account from the
 * immutable journal entries and compares it against ledger_balance_snapshots.
 * <p>
 * One SQL statement reads reconstruction + actual snapshot together, guaranteeing
 * they come from the same PostgreSQL MVCC statement snapshot. A concurrent valid
 * posting commits journal and snapshot atomically (V3 trigger), so the single
 * statement sees either both or neither — no false positives.
 * <p>
 * DRAFT entries are excluded via a derived subquery ({@code AND jt.status = 'POSTED'})
 * so only POSTED journal entries contribute to the reconstructed balance.
 * <p>
 * Sign convention (mirrors V3 trigger exactly):
 * <ul>
 *   <li>CREDIT-normal (CUSTOMER, MERCHANT, PLATFORM_FEES): CREDIT=+, DEBIT=-</li>
 *   <li>DEBIT-normal (PSP_CLEARING, PLATFORM_RESERVE): DEBIT=+, CREDIT=-</li>
 * </ul>
 */
@Service
public class SnapshotConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(SnapshotConsistencyChecker.class);

    private static final String ENTITY_TYPE = "LEDGER_ACCOUNT";

    /**
     * Single SQL statement: reconstruction + actual snapshot from the same MVCC snapshot.
     * LEFT JOIN journal entries derived subquery (POSTED only).
     * LEFT JOIN ledger_balance_snapshots.
     */
    private static final String SCAN_SQL = """
            SELECT
                la.id                                       AS ledger_account_id,
                la.account_type,
                COALESCE(
                    SUM(
                        CASE
                            WHEN la.account_type IN ('CUSTOMER', 'MERCHANT', 'PLATFORM_FEES') THEN
                                CASE WHEN pe.direction = 'CREDIT'
                                     THEN  pe.amount_minor::NUMERIC
                                     ELSE -pe.amount_minor::NUMERIC END
                            ELSE
                                CASE WHEN pe.direction = 'DEBIT'
                                     THEN  pe.amount_minor::NUMERIC
                                     ELSE -pe.amount_minor::NUMERIC END
                        END
                    ), 0::NUMERIC
                )                                           AS reconstructed_balance,
                lbs.balance_minor::NUMERIC                  AS actual_snapshot_balance
            FROM ledger_accounts la
            LEFT JOIN (
                SELECT
                    je.ledger_account_id,
                    je.direction,
                    je.amount_minor
                FROM journal_entries je
                JOIN journal_transactions jt
                  ON jt.id = je.journal_transaction_id
                 AND jt.status = 'POSTED'
            ) pe ON pe.ledger_account_id = la.id
            LEFT JOIN ledger_balance_snapshots lbs
                   ON lbs.ledger_account_id = la.id
            GROUP BY la.id, la.account_type, lbs.balance_minor
            """;

    private record SnapshotDiscrepancy(UUID accountId, ReconciliationProblemType problemType,
                                       BigDecimal expected, BigDecimal actual, String description) {}

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationItemRepository itemRepository;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public SnapshotConsistencyChecker(JdbcTemplate jdbcTemplate,
                                      ReconciliationItemRepository itemRepository,
                                      org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.itemRepository = itemRepository;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Executes Level 2 scan.
     * Each discrepancy is persisted in its own REQUIRES_NEW transaction.
     *
     * @return number of ledger accounts checked
     */
    public long check(UUID runId) {
        log.info("Level 2 (Snapshot Consistency) scan started for run {}", runId);
        java.util.List<SnapshotDiscrepancy> discrepancies = new java.util.ArrayList<>();
        long[] checked = {0};

        jdbcTemplate.query(SCAN_SQL, rs -> {
            checked[0]++;
            UUID accountId = UUID.fromString(rs.getString("ledger_account_id"));
            String accountType = rs.getString("account_type");
            BigDecimal reconstructed = rs.getBigDecimal("reconstructed_balance");
            BigDecimal actual = rs.getBigDecimal("actual_snapshot_balance"); // null if snapshot row missing

            if (actual == null) {
                discrepancies.add(new SnapshotDiscrepancy(accountId, ReconciliationProblemType.SNAPSHOT_MISSING,
                        reconstructed, null,
                        String.format("No ledger_balance_snapshots row for account %s (type=%s); reconstructed balance=%s",
                                accountId, accountType, reconstructed)));
            } else if (reconstructed.compareTo(actual) != 0) {
                discrepancies.add(new SnapshotDiscrepancy(accountId, ReconciliationProblemType.SNAPSHOT_MISMATCH,
                        reconstructed, actual,
                        String.format("Snapshot mismatch for account %s (type=%s): reconstructed=%s actual=%s delta=%s",
                                accountId, accountType, reconstructed, actual, reconstructed.subtract(actual))));
            }
        });

        for (SnapshotDiscrepancy d : discrepancies) {
            persistDiscrepancy(runId, d.accountId(), d.problemType(), d.expected(), d.actual(), d.description());
        }

        log.info("Level 2 (Snapshot Consistency) scan completed for run {}: {} accounts checked", runId, checked[0]);
        return checked[0];
    }

    public void persistDiscrepancy(UUID runId, UUID accountId, ReconciliationProblemType problemType,
                                   BigDecimal expectedValue, BigDecimal actualValue, String description) {
        transactionTemplate.execute(status -> {
            ReconciliationItem item = ReconciliationItem.builder()
                    .runId(runId)
                    .classification(ReconciliationClassification.DISCREPANCY)
                    .level(ReconciliationLevel.SNAPSHOT_CONSISTENCY)
                    .problemType(problemType)
                    .entityType(ENTITY_TYPE)
                    .entityId(accountId)
                    .expectedValue(expectedValue)
                    .actualValue(actualValue)
                    .description(description)
                    .build();
            itemRepository.saveAndFlush(item);
            log.warn("Level 2 discrepancy recorded: run={} account={} type={}", runId, accountId, problemType);
            return null;
        });
    }
}
