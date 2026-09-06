package com.ledgerguard.lab.fault;

import com.ledgerguard.lab.guard.EnvironmentGuard;
import com.ledgerguard.lab.guard.LabDatabaseTarget;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Narrowly scoped, parameter-restricted fault injector for intentional balance snapshot drift.
 * <p>
 * Strictly enforces that:
 * 1. A valid, positively authorized {@link LabDatabaseTarget} is required.
 * 2. Only a single parameterized SQL UPDATE statement can be executed against {@code ledger_balance_snapshots}.
 * 3. No arbitrary SQL queries, dynamic statements, or multi-table updates are allowed.
 */
public final class SnapshotFaultInjector {

    private static final String CORRUPT_SNAPSHOT_SQL =
            "UPDATE ledger_balance_snapshots SET balance_minor = ?, updated_at = NOW() WHERE ledger_account_id = ?";

    private SnapshotFaultInjector() {
        // Utility class
    }

    /**
     * Injects a deliberate balance drift into a specific account's balance snapshot.
     *
     * @param target authorized lab database target
     * @param dataSource live connection provider
     * @param accountId ledger account whose snapshot will be corrupted
     * @param corruptedBalanceMinor new mutated balance amount in minor units
     * @return number of rows updated (must be exactly 1)
     */
    public static int corruptSnapshotBalance(
            LabDatabaseTarget target,
            DataSource dataSource,
            UUID accountId,
            long corruptedBalanceMinor
    ) throws SQLException {
        Objects.requireNonNull(target, "LabDatabaseTarget must not be null");
        Objects.requireNonNull(dataSource, "DataSource must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");

        try (Connection conn = dataSource.getConnection()) {
            EnvironmentGuard.validateConnection(target, conn);

            try (PreparedStatement ps = conn.prepareStatement(CORRUPT_SNAPSHOT_SQL)) {
                ps.setLong(1, corruptedBalanceMinor);
                ps.setObject(2, accountId);
                return ps.executeUpdate();
            }
        }
    }
}
