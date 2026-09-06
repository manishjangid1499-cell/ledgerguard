package com.ledgerguard.lab.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EnvironmentGuard Fail-Closed Positive Authorization Tests")
class EnvironmentGuardTest {

    @Test
    @DisplayName("Accepts authorized local lab database target (localhost with lab marker)")
    void acceptsAuthorizedLocalLabDatabase() {
        DataSource safeDs = createMockDataSource("jdbc:postgresql://localhost:5432/ledgerguard_lab_test", "ledgerguard_lab_test");
        assertThatCode(() -> EnvironmentGuard.assertLabEnvironment(safeDs))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DENY BY DEFAULT: Rejects innocuous-looking private IP (10.0.0.8)")
    void rejectsPrivateIpAddress() {
        DataSource privateIpDs = createMockDataSource("jdbc:postgresql://10.0.0.8:5432/ledgerguard_test", "ledgerguard_test");
        assertThatThrownBy(() -> EnvironmentGuard.assertLabEnvironment(privateIpDs))
                .isInstanceOf(UnsafeEnvironmentException.class)
                .hasMessageContaining("not an authorized local test environment");
    }

    @Test
    @DisplayName("DENY BY DEFAULT: Rejects internal domain name (db.internal)")
    void rejectsInternalDomain() {
        DataSource internalDs = createMockDataSource("jdbc:postgresql://db.internal:5432/ledgerguard_test", "ledgerguard_test");
        assertThatThrownBy(() -> EnvironmentGuard.assertLabEnvironment(internalDs))
                .isInstanceOf(UnsafeEnvironmentException.class)
                .hasMessageContaining("not an authorized local test environment");
    }

    @Test
    @DisplayName("DENY BY DEFAULT: Rejects remote domain name (example.com)")
    void rejectsRemoteDomain() {
        DataSource remoteDs = createMockDataSource("jdbc:postgresql://example.com:5432/ledgerguard_test", "ledgerguard_test");
        assertThatThrownBy(() -> EnvironmentGuard.assertLabEnvironment(remoteDs))
                .isInstanceOf(UnsafeEnvironmentException.class)
                .hasMessageContaining("not an authorized local test environment");
    }

    @Test
    @DisplayName("DEFENSE IN DEPTH: Rejects database with 'prod' in URL")
    void rejectsProductionDatabaseUrl() {
        DataSource prodDs = createMockDataSource("jdbc:postgresql://prod-db.internal:5432/ledgerguard_test", "ledgerguard_test");
        assertThatThrownBy(() -> EnvironmentGuard.assertLabEnvironment(prodDs))
                .isInstanceOf(UnsafeEnvironmentException.class);
    }

    @Test
    @DisplayName("DEFENSE IN DEPTH: Rejects database with 'staging' in catalog")
    void rejectsStagingCatalog() {
        DataSource stagingDs = createMockDataSource("jdbc:postgresql://localhost:5432/ledgerguard_staging", "ledgerguard_staging");
        assertThatThrownBy(() -> EnvironmentGuard.assertLabEnvironment(stagingDs))
                .isInstanceOf(UnsafeEnvironmentException.class)
                .hasMessageContaining("forbidden environment marker 'staging'");
    }

    @Test
    @DisplayName("DENY BY DEFAULT: Rejects database missing positive 'lab' or 'test' naming markers")
    void rejectsUnmarkedDatabase() {
        DataSource unmarkedDs = createMockDataSource("jdbc:postgresql://localhost:5432/ledgerguard", "ledgerguard");
        assertThatThrownBy(() -> EnvironmentGuard.assertLabEnvironment(unmarkedDs))
                .isInstanceOf(UnsafeEnvironmentException.class)
                .hasMessageContaining("lacks positive 'lab' or 'test' naming markers");
    }

    @Test
    @DisplayName("LabDatabaseTarget positive authorization works for ephemeral testcontainer")
    void labDatabaseTargetPositiveAuthorization() {
        String token = LabDatabaseTarget.generateOwnershipToken();
        LabDatabaseTarget target = LabDatabaseTarget.authorizeEphemeral(
                "jdbc:postgresql://localhost:54321/ledgerguard_lab_test",
                "ledgerguard_lab_test",
                token
        );
        assertThatCode(() -> {
            Connection mockConn = createMockConnection("jdbc:postgresql://localhost:54321/ledgerguard_lab_test", "ledgerguard_lab_test");
            EnvironmentGuard.validateConnection(target, mockConn);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("LabDatabaseTarget rejects non-local URL during creation")
    void labDatabaseTargetRejectsNonLocalUrl() {
        assertThatThrownBy(() -> LabDatabaseTarget.authorizeEphemeral(
                "jdbc:postgresql://10.0.0.8:5432/ledgerguard_lab_test",
                "ledgerguard_lab_test",
                "token"
        )).isInstanceOf(UnsafeEnvironmentException.class);
    }

    private DataSource createMockDataSource(String url, String catalog) {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return createMockConnection(url, catalog);
                    }
                    return null;
                }
        );
    }

    private Connection createMockConnection(String url, String catalog) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return createMockMetaData(url);
                    }
                    if ("getCatalog".equals(method.getName())) {
                        return catalog;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return null;
                }
        );
    }

    private DatabaseMetaData createMockMetaData(String url) {
        return (DatabaseMetaData) java.lang.reflect.Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getURL".equals(method.getName())) {
                        return url;
                    }
                    return null;
                }
        );
    }
}
