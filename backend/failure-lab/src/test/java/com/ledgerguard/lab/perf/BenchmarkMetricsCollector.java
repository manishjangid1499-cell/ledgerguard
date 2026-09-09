package com.ledgerguard.lab.perf;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects runtime Hikari connection pool metrics, Micrometer connection acquisition metrics,
 * and database lock/deadlock statistics using an external observer JDBC connection.
 */
public class BenchmarkMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkMetricsCollector.class);

    private final HikariDataSource hikariDataSource;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final MeterRegistry meterRegistry;

    private Connection observerConnection;
    private Thread samplerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Hikari pool samples
    private volatile int maxActiveConnections = 0;
    private volatile int minIdleConnections = Integer.MAX_VALUE;
    private volatile int maxPendingThreads = 0;
    private volatile int samplesWithPendingThreads = 0;

    // Database lock waiter samples
    private volatile int maxDatabaseLockWaiters = 0;
    private volatile int samplesWithDatabaseLockWaiters = 0;

    // Deadlocks
    private long deadlocksBefore = 0;
    private long deadlocksAfter = 0;
    private long deadlocksDelta = 0;

    // Micrometer acquire timer
    private boolean acquisitionMetricAvailable = false;
    private String acquisitionMeterName = null;
    private Timer acquireTimer = null;
    private long acquireCountBefore = 0;
    private double acquireTotalMsBefore = 0.0;
    private long acquireCountDelta = 0;
    private double acquireTotalMsDelta = 0.0;

    public BenchmarkMetricsCollector(
            HikariDataSource hikariDataSource,
            String jdbcUrl,
            String username,
            String password,
            MeterRegistry meterRegistry
    ) {
        this.hikariDataSource = hikariDataSource;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.meterRegistry = meterRegistry;
    }

    public void start() throws SQLException {
        // Dedicated observer connection directly via DriverManager (outside Hikari pool)
        this.observerConnection = DriverManager.getConnection(jdbcUrl, username, password);

        // Deadlocks baseline
        this.deadlocksBefore = queryDeadlocks(observerConnection);

        // Locate Micrometer connection acquisition timer
        locateAcquisitionTimer();
        if (acquireTimer != null) {
            this.acquisitionMetricAvailable = true;
            this.acquireCountBefore = acquireTimer.count();
            this.acquireTotalMsBefore = acquireTimer.totalTime(TimeUnit.MILLISECONDS);
        }

        // Reset sampled stats
        this.maxActiveConnections = 0;
        this.minIdleConnections = Integer.MAX_VALUE;
        this.maxPendingThreads = 0;
        this.samplesWithPendingThreads = 0;
        this.maxDatabaseLockWaiters = 0;
        this.samplesWithDatabaseLockWaiters = 0;

        // Start background sampling at ~20ms intervals
        this.running.set(true);
        this.samplerThread = new Thread(this::samplingLoop, "benchmark-metrics-sampler");
        this.samplerThread.setDaemon(true);
        this.samplerThread.start();
    }

    public void stop() {
        this.running.set(false);
        if (samplerThread != null) {
            samplerThread.interrupt();
            try {
                samplerThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (observerConnection != null) {
            try {
                this.deadlocksAfter = queryDeadlocks(observerConnection);
                this.deadlocksDelta = Math.max(0, deadlocksAfter - deadlocksBefore);
            } catch (SQLException e) {
                log.warn("Failed to query deadlocks delta: {}", e.getMessage());
            } finally {
                try {
                    observerConnection.close();
                } catch (SQLException ignored) {
                }
            }
        }

        if (acquireTimer != null) {
            long countAfter = acquireTimer.count();
            double totalMsAfter = acquireTimer.totalTime(TimeUnit.MILLISECONDS);
            this.acquireCountDelta = Math.max(0, countAfter - acquireCountBefore);
            this.acquireTotalMsDelta = Math.max(0.0, totalMsAfter - acquireTotalMsBefore);
        }

        if (minIdleConnections == Integer.MAX_VALUE) {
            minIdleConnections = 0;
        }
    }

    private void samplingLoop() {
        HikariPoolMXBean poolBean = hikariDataSource.getHikariPoolMXBean();
        String lockWaitersSql = """
                SELECT count(*)
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND wait_event_type = 'Lock'
                  AND pid <> pg_backend_pid();
                """;

        while (running.get()) {
            try {
                if (poolBean != null) {
                    int active = poolBean.getActiveConnections();
                    int idle = poolBean.getIdleConnections();
                    int pending = poolBean.getThreadsAwaitingConnection();

                    if (active > maxActiveConnections) {
                        maxActiveConnections = active;
                    }
                    if (idle < minIdleConnections) {
                        minIdleConnections = idle;
                    }
                    if (pending > maxPendingThreads) {
                        maxPendingThreads = pending;
                    }
                    if (pending > 0) {
                        samplesWithPendingThreads++;
                    }
                }

                if (observerConnection != null && !observerConnection.isClosed()) {
                    try (PreparedStatement ps = observerConnection.prepareStatement(lockWaitersSql);
                         ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            int lockWaiters = rs.getInt(1);
                            if (lockWaiters > maxDatabaseLockWaiters) {
                                maxDatabaseLockWaiters = lockWaiters;
                            }
                            if (lockWaiters > 0) {
                                samplesWithDatabaseLockWaiters++;
                            }
                        }
                    }
                }

                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                // Ignore transient observer errors during sampling
            }
        }
    }

    private long queryDeadlocks(Connection conn) throws SQLException {
        String sql = "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database();";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return 0;
    }

    private void locateAcquisitionTimer() {
        if (meterRegistry == null) {
            return;
        }
        for (Meter meter : meterRegistry.getMeters()) {
            String name = meter.getId().getName();
            if (meter instanceof Timer timer && (name.equals("hikaricp.connections.acquire") || name.contains("connections.acquire"))) {
                this.acquireTimer = timer;
                this.acquisitionMeterName = name;
                return;
            }
        }
    }

    public int getMaxActiveConnections() {
        return maxActiveConnections;
    }

    public int getMinIdleConnections() {
        return minIdleConnections;
    }

    public int getMaxPendingThreads() {
        return maxPendingThreads;
    }

    public int getSamplesWithPendingThreads() {
        return samplesWithPendingThreads;
    }

    public int getMaxDatabaseLockWaiters() {
        return maxDatabaseLockWaiters;
    }

    public int getSamplesWithDatabaseLockWaiters() {
        return samplesWithDatabaseLockWaiters;
    }

    public long getDeadlocksBefore() {
        return deadlocksBefore;
    }

    public long getDeadlocksAfter() {
        return deadlocksAfter;
    }

    public long getDeadlocksDelta() {
        return deadlocksDelta;
    }

    public boolean isAcquisitionMetricAvailable() {
        return acquisitionMetricAvailable;
    }

    public String getAcquisitionMeterName() {
        return acquisitionMeterName;
    }

    public long getAcquisitionCountDelta() {
        return acquireCountDelta;
    }

    public double getAcquisitionTotalMsDelta() {
        return acquireTotalMsDelta;
    }
}
