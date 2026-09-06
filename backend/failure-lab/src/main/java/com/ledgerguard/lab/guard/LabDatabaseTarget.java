package com.ledgerguard.lab.guard;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * Positively authorized lab database target.
 * <p>
 * Implements DENY BY DEFAULT: an instance can only be created with positive ownership proof
 * (e.g. ephemeral Testcontainers token and port) and cannot be instantiated with arbitrary remote targets.
 */
public final class LabDatabaseTarget {

    private final String jdbcUrl;
    private final String databaseName;
    private final String ownershipToken;
    private final String host;
    private final int port;

    private LabDatabaseTarget(String jdbcUrl, String databaseName, String ownershipToken, String host, int port) {
        this.jdbcUrl = jdbcUrl;
        this.databaseName = databaseName;
        this.ownershipToken = ownershipToken;
        this.host = host;
        this.port = port;
    }

    /**
     * Factory for creating an authorized ephemeral lab target from known test harness parameters.
     * Requires positive local host and lab database markers.
     */
    public static LabDatabaseTarget authorizeEphemeral(String jdbcUrl, String databaseName, String ownershipToken) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        Objects.requireNonNull(databaseName, "databaseName must not be null");
        Objects.requireNonNull(ownershipToken, "ownershipToken must not be null");

        if (ownershipToken.trim().isEmpty()) {
            throw new UnsafeEnvironmentException("Ownership token must not be blank");
        }

        ParsedJdbc parsed = parseJdbcUrl(jdbcUrl);
        EnvironmentGuard.assertSafeTarget(parsed.host(), parsed.port(), parsed.path(), databaseName, jdbcUrl);

        return new LabDatabaseTarget(jdbcUrl, databaseName, ownershipToken, parsed.host(), parsed.port());
    }

    /**
     * Convenience factory for creating a random unforgeable ownership token.
     */
    public static String generateOwnershipToken() {
        return UUID.randomUUID().toString();
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getOwnershipToken() {
        return ownershipToken;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    private static ParsedJdbc parseJdbcUrl(String jdbcUrl) {
        // Expected format: jdbc:postgresql://host:port/databaseName?...
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
            throw new UnsafeEnvironmentException("Malformed JDBC URL cannot be authorized: " + jdbcUrl, e);
        }
    }

    private record ParsedJdbc(String host, int port, String path) {}
}
