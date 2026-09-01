package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSchemaAndConstraintTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway applies V1 migration and creates users and refresh_tokens tables")
    void flywayMigrationCreatesIdentityTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        assertThat(tables).contains("users", "refresh_tokens", "flyway_schema_history");
        assertThat(tables).doesNotContain(
                "wallets", "outbox_events", "reconciliation_records",
                "balance_holds", "account_balances"
        );
    }

    @Test
    @DisplayName("Users table columns match Phase 4 specification")
    void usersTableColumnsMatchSpec() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'users'",
                String.class
        );

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "email", "password_hash", "role", "status", "created_at", "updated_at"
        );
    }

    @Test
    @DisplayName("Refresh tokens table columns match Phase 4 specification")
    void refreshTokensTableColumnsMatchSpec() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'refresh_tokens'",
                String.class
        );

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "user_id", "token_hash", "created_at", "expires_at", "revoked_at"
        );
    }

    @Test
    @DisplayName("Refresh tokens indexes contain user_id index and unique constraint index without redundant explicit index")
    void refreshTokensIndexCheck() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'refresh_tokens'",
                String.class
        );

        assertThat(indexes).contains("refresh_tokens_pkey", "uq_refresh_tokens_token_hash", "idx_refresh_tokens_user_id");
        assertThat(indexes).doesNotContain("idx_refresh_tokens_token_hash");
    }
}
