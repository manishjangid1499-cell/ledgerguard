package com.ledgerguard.e2e.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class E2EDatabaseProbe {

    private final String jdbcHost;
    private final int jdbcPort;
    private final String username;
    private final String password;

    public E2EDatabaseProbe(String jdbcHost, int jdbcPort, String username, String password) {
        this.jdbcHost = jdbcHost;
        this.jdbcPort = jdbcPort;
        this.username = username;
        this.password = password;
    }

    private Connection getConnection(String databaseName) throws SQLException {
        String url = String.format("jdbc:postgresql://%s:%d/%s", jdbcHost, jdbcPort, databaseName);
        return DriverManager.getConnection(url, username, password);
    }

    public void initializeDatabases(String... databaseNames) {
        try (Connection conn = getConnection("ledgerguard");
             Statement stmt = conn.createStatement()) {

            for (String dbName : databaseNames) {
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'")) {
                    if (!rs.next()) {
                        stmt.execute("CREATE DATABASE " + dbName);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize auxiliary databases", e);
        }
    }

    public void seedSystemAccountsIfAbsent() {
        String[] systemTypes = {"PSP_CLEARING", "PLATFORM_RESERVE", "PLATFORM_FEES"};
        try (Connection conn = getConnection("ledgerguard")) {
            for (String type : systemTypes) {
                try (PreparedStatement checkStmt = conn.prepareStatement(
                        "SELECT 1 FROM ledger_accounts WHERE account_type = ? AND owner_user_id IS NULL")) {
                    checkStmt.setString(1, type);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (!rs.next()) {
                            try (PreparedStatement insertStmt = conn.prepareStatement(
                                    "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                                    "VALUES (?, NULL, ?, 'INR', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                                insertStmt.setObject(1, UUID.randomUUID());
                                insertStmt.setString(2, type);
                                insertStmt.executeUpdate();
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to seed system accounts in ledgerguard", e);
        }
    }

    public List<String> getAppliedFlywayVersions(String databaseName) {
        List<String> versions = new ArrayList<>();
        try (Connection conn = getConnection(databaseName);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank")) {
            while (rs.next()) {
                String v = rs.getString("version");
                if (v != null) {
                    versions.add(v);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read flyway_schema_history from " + databaseName, e);
        }
        return versions;
    }

    public long getDoubleEntryImbalanceMinor() {
        String sql = "SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0) " +
                "FROM journal_entries je " +
                "JOIN journal_transactions jt ON je.journal_transaction_id = jt.id " +
                "WHERE jt.status = 'POSTED'";
        try (Connection conn = getConnection("ledgerguard");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to calculate double-entry balance", e);
        }
    }

    public int countSnapshotMismatches() {
        String sql = """
            SELECT COUNT(*) FROM ledger_balance_snapshots s
            JOIN (
                SELECT je.ledger_account_id,
                       SUM(CASE
                           WHEN la.account_type IN ('CUSTOMER', 'MERCHANT', 'PLATFORM_FEES') THEN
                               CASE WHEN je.direction = 'CREDIT' THEN je.amount_minor ELSE -je.amount_minor END
                           ELSE
                               CASE WHEN je.direction = 'DEBIT' THEN je.amount_minor ELSE -je.amount_minor END
                       END) as computed_balance
                FROM journal_entries je
                JOIN journal_transactions jt ON je.journal_transaction_id = jt.id
                JOIN ledger_accounts la ON je.ledger_account_id = la.id
                WHERE jt.status = 'POSTED'
                GROUP BY je.ledger_account_id, la.account_type
            ) c ON s.ledger_account_id = c.ledger_account_id
            WHERE s.balance_minor <> c.computed_balance
            """;
        try (Connection conn = getConnection("ledgerguard");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check snapshot mismatches", e);
        }
    }

    public void assertDoubleEntrySystemBalanced() {
        long imbalance = getDoubleEntryImbalanceMinor();
        if (imbalance != 0) {
            throw new AssertionError("Double-entry imbalance detected: " + imbalance);
        }
    }

    public void assertSnapshotParity() {
        int mismatches = countSnapshotMismatches();
        if (mismatches != 0) {
            throw new AssertionError("Snapshot parity violation: " + mismatches + " account(s) mismatch");
        }
    }

    public boolean accountExists(UUID accountId) {
        String sql = "SELECT 1 FROM ledger_accounts WHERE id = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, accountId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query ledger_accounts", e);
        }
    }

    public long getSnapshotBalance(UUID accountId) {
        String sql = "SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, accountId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("balance_minor");
                }
                throw new IllegalStateException("Snapshot not found for account: " + accountId);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query balance_snapshots", e);
        }
    }

    public void assertZeroSumTransfer(UUID accountA, UUID accountB, long initialBalanceA, long transferAmount) {
        long balA = getSnapshotBalance(accountA);
        long balB = getSnapshotBalance(accountB);
        if (balA != (initialBalanceA - transferAmount)) {
            throw new AssertionError("Sender balance mismatch: expected " + (initialBalanceA - transferAmount) + " but was " + balA);
        }
        if (balB != transferAmount) {
            throw new AssertionError("Recipient balance mismatch: expected " + transferAmount + " but was " + balB);
        }
        if ((balA + balB) != initialBalanceA) {
            throw new AssertionError("Conservation of funds violated: total sum " + (balA + balB) + " != " + initialBalanceA);
        }
    }

    public void assertOutboxEventPublished(String aggregateType, UUID aggregateId) {
        String sql = "SELECT status FROM outbox_events WHERE aggregate_id = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, aggregateId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("No outbox event found for aggregateId: " + aggregateId);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check outbox event", e);
        }
    }

    public int countOutboxEventsByStatus(String status) {
        String sql = "SELECT COUNT(*) FROM outbox_events WHERE status = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query outbox_events", e);
        }
    }

    public int countProcessedEventsInWorker(UUID eventId) {
        String sql = "SELECT COUNT(*) FROM processed_events WHERE event_id = ?";
        try (Connection conn = getConnection("notification_worker");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, eventId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query processed_events in notification_worker", e);
        }
    }

    public int countNotificationDeliveries(UUID eventId) {
        String sql = "SELECT COUNT(*) FROM notification_deliveries WHERE event_id = ? AND status = 'DELIVERED'";
        try (Connection conn = getConnection("notification_worker");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, eventId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query notification_deliveries", e);
        }
    }

    public int countNotificationDeliveriesByAggregateId(UUID aggregateId) {
        String sql = "SELECT COUNT(*) FROM notification_deliveries WHERE aggregate_id = ? AND status = 'DELIVERED'";
        try (Connection conn = getConnection("notification_worker");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, aggregateId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query notification_deliveries by aggregate_id", e);
        }
    }

    public String getProviderOperationStatus(UUID clientOperationId) {
        String sql = "SELECT status FROM provider_operations WHERE client_operation_id = ?";
        try (Connection conn = getConnection("psp_simulator");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, clientOperationId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("status");
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query provider_operations in psp_simulator", e);
        }
    }

    public String getPayoutStatus(UUID payoutId) {
        String sql = "SELECT status FROM payouts WHERE id = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, payoutId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("status");
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query payout status", e);
        }
    }

    public String getBalanceHoldStatus(UUID holdId) {
        String sql = "SELECT status FROM balance_holds WHERE id = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, holdId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("status");
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query balance hold status", e);
        }
    }

    public UUID getFundingJournalTransactionId(UUID fundingId) {
        String sql = "SELECT journal_transaction_id FROM funding_operations WHERE id = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, fundingId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("journal_transaction_id", UUID.class);
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query funding journal_transaction_id", e);
        }
    }

    public UUID getPayoutJournalTransactionId(UUID payoutId) {
        String sql = "SELECT journal_transaction_id FROM payouts WHERE id = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, payoutId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("journal_transaction_id", UUID.class);
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query payout journal_transaction_id", e);
        }
    }

    public UUID getPayoutHoldId(UUID payoutId) {
        String sql = "SELECT balance_hold_id FROM payouts WHERE id = ?";
        try (Connection conn = getConnection("ledgerguard");
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, payoutId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("balance_hold_id", UUID.class);
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query payout balance_hold_id", e);
        }
    }
}
