package com.ledgerguard.lab.guard;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed safety guard enforcing DENY-BY-DEFAULT positive authorization.
 * <p>
 * Fault injection, snapshot corruption, or chaos operations are permitted ONLY when:
 * 1. An explicit, positively authorized {@link LabDatabaseTarget} is provided with an in-process ownership token.
 * 2. The target host is strictly local (localhost, 127.0.0.1, [::1], or testcontainers).
 * 3. The database catalog/path contains positive test/lab indicators.
 * 4. Defense-in-depth: No forbidden environment keywords (prod, staging, live, cloud, replica) are present.
 */
public final class EnvironmentGuard {

    private static final Set<String> ALLOWED_LOCAL_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "[::1]",
            "testcontainers"
    );

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "prod",
            "production",
            "staging",
            "stage",
            "live",
            "cloud",
            "replica"
    );

    private EnvironmentGuard() {
        // Utility class
    }

    /**
     * Asserts that a target specification is positively safe for lab use.
     * Throws {@link UnsafeEnvironmentException} on any ambiguity or non-local host.
     */
    public static void assertSafeTarget(String host, int port, String databasePath, String catalog, String fullUrl) {
        if (host == null || host.trim().isEmpty()) {
            throw new UnsafeEnvironmentException("DENY BY DEFAULT: Target host is missing or blank");
        }

        String hostLower = host.toLowerCase(Locale.ROOT);

        // 1. Positive local host authorization (DENY non-local, LAN, remote DNS, cloud IPs)
        boolean isLocalHost = ALLOWED_LOCAL_HOSTS.contains(hostLower)
                || hostLower.endsWith(".localhost");

        if (!isLocalHost) {
            throw new UnsafeEnvironmentException(
                    "DENY BY DEFAULT: Target host '" + host + "' is not an authorized local test environment. "
                            + "Only localhost, 127.0.0.1, [::1], and testcontainers are permitted.");
        }

        String urlClean = fullUrl != null ? fullUrl.toLowerCase(Locale.ROOT).split("\\?")[0] : "";
        String dbClean = databasePath != null ? databasePath.toLowerCase(Locale.ROOT) : "";
        String catClean = catalog != null ? catalog.toLowerCase(Locale.ROOT) : "";

        // 2. Defense-in-depth: Reject forbidden environment keywords
        for (String forbidden : FORBIDDEN_KEYWORDS) {
            if (urlClean.contains(forbidden) || catClean.contains(forbidden) || dbClean.contains(forbidden)) {
                throw new UnsafeEnvironmentException(
                        "SAFETY ABORT: Target contains forbidden environment marker '" + forbidden + "': " + fullUrl);
            }
        }

        // 3. Positive lab/test marker verification
        boolean hasLabMarker = urlClean.contains("test")
                || urlClean.contains("lab")
                || urlClean.contains("testcontainers")
                || catClean.contains("test")
                || catClean.contains("lab")
                || dbClean.contains("test")
                || dbClean.contains("lab");

        if (!hasLabMarker) {
            throw new UnsafeEnvironmentException(
                    "DENY BY DEFAULT: Target database '" + catalog + "' lacks positive 'lab' or 'test' naming markers: " + fullUrl);
        }
    }

    /**
     * Validates an active Connection against an authorized {@link LabDatabaseTarget}.
     */
    public static void validateConnection(LabDatabaseTarget target, Connection connection) {
        if (target == null) {
            throw new UnsafeEnvironmentException("DENY BY DEFAULT: LabDatabaseTarget is null");
        }
        if (target.getOwnershipToken() == null || target.getOwnershipToken().trim().isEmpty()) {
            throw new UnsafeEnvironmentException("DENY BY DEFAULT: Ownership token is missing or invalid");
        }
        if (connection == null) {
            throw new UnsafeEnvironmentException("DENY BY DEFAULT: Connection is null");
        }

        try {
            DatabaseMetaData meta = connection.getMetaData();
            String liveUrl = meta.getURL();
            String liveCatalog = connection.getCatalog();

            // Interrogate live connection
            ParsedJdbc parsed = parseJdbc(liveUrl);
            assertSafeTarget(parsed.host, parsed.port, parsed.path, liveCatalog, liveUrl);

            // Ensure live URL matches authorized target URL (before query params)
            String targetBase = target.getJdbcUrl().split("\\?")[0];
            String liveBase = (liveUrl != null ? liveUrl : "").split("\\?")[0];
            if (!targetBase.equalsIgnoreCase(liveBase)) {
                throw new UnsafeEnvironmentException("SAFETY MISMATCH: Live connection URL '" + liveBase
                        + "' does not match authorized target URL '" + targetBase + "'");
            }
        } catch (SQLException e) {
            throw new UnsafeEnvironmentException("Failed to verify database connection metadata", e);
        }
    }

    /**
     * Asserts that the provided DataSource targets an authorized lab environment.
     */
    public static void assertLabEnvironment(DataSource dataSource) {
        if (dataSource == null) {
            throw new UnsafeEnvironmentException("DENY BY DEFAULT: DataSource must not be null");
        }

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String url = meta.getURL();
            String catalog = conn.getCatalog();
            ParsedJdbc parsed = parseJdbc(url);
            assertSafeTarget(parsed.host, parsed.port, parsed.path, catalog, url);
        } catch (SQLException e) {
            throw new UnsafeEnvironmentException("Failed to interrogate database environment for safety verification", e);
        }
    }

    private static ParsedJdbc parseJdbc(String jdbcUrl) {
        if (jdbcUrl == null) {
            return new ParsedJdbc("", -1, "");
        }
        String cleanUrl = jdbcUrl.replace("jdbc:", "");
        try {
            URI uri = URI.create(cleanUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            if (path != null && path.startsWith("/")) {
                path = path.substring(1);
            }
            return new ParsedJdbc(host != null ? host : "", port, path != null ? path : "");
        } catch (Exception e) {
            throw new UnsafeEnvironmentException("Malformed JDBC URL cannot be parsed: " + jdbcUrl, e);
        }
    }

    private record ParsedJdbc(String host, int port, String path) {}
}
