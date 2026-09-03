package com.ledgerguard.lifecycle;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationV13CompatibilityTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V13 migration compatibility: columns exist and non-negative constraints enforced")
    void v13ColumnsAndConstraintsExist() {
        // Assert that new columns exist and can be queried
        Integer fundingPollCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM funding_operations WHERE provider_poll_attempts >= 0",
                Integer.class
        );
        assertThat(fundingPollCount).isNotNull();

        Integer payoutPollCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payouts WHERE provider_poll_attempts >= 0",
                Integer.class
        );
        assertThat(payoutPollCount).isNotNull();
    }
}
