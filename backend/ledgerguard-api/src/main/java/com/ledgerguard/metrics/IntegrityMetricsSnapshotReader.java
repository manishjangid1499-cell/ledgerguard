package com.ledgerguard.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes a single consolidated SQL statement returning all three DB-backed metrics
 * within one read-only database round trip and one statement-level MVCC snapshot.
 */
@Component
public class IntegrityMetricsSnapshotReader {

    private static final String SNAPSHOT_SQL = """
            SELECT
                (
                    SELECT COUNT(*)
                    FROM (
                        SELECT jt.id
                        FROM journal_transactions jt
                        LEFT JOIN journal_entries je
                            ON je.journal_transaction_id = jt.id
                        WHERE jt.status = 'POSTED'
                        GROUP BY jt.id
                        HAVING COUNT(je.id) < 2
                            OR COUNT(je.id) FILTER (WHERE je.direction = 'DEBIT') < 1
                            OR COUNT(je.id) FILTER (WHERE je.direction = 'CREDIT') < 1
                            OR COALESCE(
                                SUM(je.amount_minor::NUMERIC) FILTER (WHERE je.direction = 'DEBIT'),
                                0::NUMERIC
                            ) <>
                            COALESCE(
                                SUM(je.amount_minor::NUMERIC) FILTER (WHERE je.direction = 'CREDIT'),
                                0::NUMERIC
                            )
                    ) invalid_journals
                ) AS unbalanced_journal_count,

                (
                    SELECT COUNT(*)
                    FROM reconciliation_cases rc
                    JOIN reconciliation_items ri
                        ON ri.id = rc.reconciliation_item_id
                    WHERE rc.status IN ('OPEN', 'IN_REVIEW')
                      AND ri.classification = 'DISCREPANCY'
                ) AS reconciliation_discrepancies,

                (
                    SELECT COALESCE(
                        GREATEST(
                            0,
                            EXTRACT(
                                EPOCH FROM (
                                    CURRENT_TIMESTAMP - MIN(created_at)
                                )
                            )
                        ),
                        0
                    )
                    FROM outbox_events
                    WHERE status = 'PENDING'
                ) AS outbox_lag_seconds
            """;

    private final JdbcTemplate jdbcTemplate;

    public IntegrityMetricsSnapshotReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public IntegritySnapshot readSnapshot() {
        return jdbcTemplate.queryForObject(SNAPSHOT_SQL, (rs, rowNum) -> new IntegritySnapshot(
                rs.getLong("unbalanced_journal_count"),
                rs.getLong("reconciliation_discrepancies"),
                rs.getDouble("outbox_lag_seconds")
        ));
    }
}