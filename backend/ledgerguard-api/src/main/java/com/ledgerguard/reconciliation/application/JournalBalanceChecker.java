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
 * Level 1 reconciliation: Journal Balance.
 * <p>
 * Scans all POSTED journal transactions in a single SQL statement using LEFT JOIN
 * so that zero-entry POSTED journals are visible (they yield total_count = 0).
 * <p>
 * POSTED journal entries are permanently immutable (V2 trigger). Once a journal
 * is POSTED its entry set is frozen, making statement-level MVCC sufficient for
 * correctness — there is no concurrent mutation possible on POSTED data.
 * <p>
 * All monetary aggregation uses NUMERIC (unbounded) to avoid BIGINT overflow.
 */
@Service
public class JournalBalanceChecker {

    private static final Logger log = LoggerFactory.getLogger(JournalBalanceChecker.class);

    private static final String ENTITY_TYPE = "JOURNAL_TRANSACTION";

    private static final String SCAN_SQL = """
            SELECT
                jt.id                                                                      AS journal_transaction_id,
                COALESCE(
                    SUM(je.amount_minor::NUMERIC) FILTER (WHERE je.direction = 'DEBIT'),
                    0::NUMERIC
                )                                                                          AS debit_sum,
                COALESCE(
                    SUM(je.amount_minor::NUMERIC) FILTER (WHERE je.direction = 'CREDIT'),
                    0::NUMERIC
                )                                                                          AS credit_sum,
                COUNT(je.id) FILTER (WHERE je.direction = 'DEBIT')                        AS debit_count,
                COUNT(je.id) FILTER (WHERE je.direction = 'CREDIT')                       AS credit_count,
                COUNT(je.id)                                                               AS total_count
            FROM journal_transactions jt
            LEFT JOIN journal_entries je ON je.journal_transaction_id = jt.id
            WHERE jt.status = 'POSTED'
            GROUP BY jt.id
            """;

    private record JournalDiscrepancy(UUID journalId, ReconciliationProblemType problemType,
                                      BigDecimal debitSum, BigDecimal creditSum, String description) {}

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationItemRepository itemRepository;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public JournalBalanceChecker(JdbcTemplate jdbcTemplate,
                                 ReconciliationItemRepository itemRepository,
                                 org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.itemRepository = itemRepository;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Executes Level 1 scan.
     * Each discrepancy is persisted in its own REQUIRES_NEW transaction.
     *
     * @return number of POSTED journal transactions checked
     */
    public long check(UUID runId) {
        log.info("Level 1 (Journal Balance) scan started for run {}", runId);
        java.util.List<JournalDiscrepancy> discrepancies = new java.util.ArrayList<>();
        long[] checked = {0};

        jdbcTemplate.query(SCAN_SQL, rs -> {
            checked[0]++;
            UUID journalId = UUID.fromString(rs.getString("journal_transaction_id"));
            long totalCount = rs.getLong("total_count");
            long debitCount = rs.getLong("debit_count");
            long creditCount = rs.getLong("credit_count");
            BigDecimal debitSum = rs.getBigDecimal("debit_sum");
            BigDecimal creditSum = rs.getBigDecimal("credit_sum");

            if (totalCount < 2 || debitCount < 1 || creditCount < 1) {
                discrepancies.add(new JournalDiscrepancy(journalId, ReconciliationProblemType.MALFORMED_JOURNAL,
                        debitSum, creditSum,
                        String.format("Malformed POSTED journal %s: total_entries=%d debit_entries=%d credit_entries=%d",
                                journalId, totalCount, debitCount, creditCount)));
            } else if (debitSum.compareTo(creditSum) != 0) {
                discrepancies.add(new JournalDiscrepancy(journalId, ReconciliationProblemType.UNBALANCED_JOURNAL,
                        debitSum, creditSum,
                        String.format("Unbalanced POSTED journal %s: debit_sum=%s credit_sum=%s delta=%s",
                                journalId, debitSum, creditSum, debitSum.subtract(creditSum))));
            }
        });

        for (JournalDiscrepancy d : discrepancies) {
            persistDiscrepancy(runId, d.journalId(), d.problemType(), d.debitSum(), d.creditSum(), d.description());
        }

        log.info("Level 1 (Journal Balance) scan completed for run {}: {} journals checked", runId, checked[0]);
        return checked[0];
    }

    public void persistDiscrepancy(UUID runId, UUID journalId, ReconciliationProblemType problemType,
                                   BigDecimal expectedValue, BigDecimal actualValue, String description) {
        transactionTemplate.execute(status -> {
            ReconciliationItem item = ReconciliationItem.builder()
                    .runId(runId)
                    .classification(ReconciliationClassification.DISCREPANCY)
                    .level(ReconciliationLevel.JOURNAL_BALANCE)
                    .problemType(problemType)
                    .entityType(ENTITY_TYPE)
                    .entityId(journalId)
                    .expectedValue(expectedValue)
                    .actualValue(actualValue)
                    .description(description)
                    .build();
            itemRepository.saveAndFlush(item);
            log.warn("Level 1 discrepancy recorded: run={} journal={} type={}", runId, journalId, problemType);
            return null;
        });
    }
}
