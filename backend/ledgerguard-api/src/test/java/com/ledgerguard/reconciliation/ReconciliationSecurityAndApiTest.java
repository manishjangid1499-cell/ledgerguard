package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.shared.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Reconciliation security authorization, bounded pagination, and exact numeric contracts")
class ReconciliationSecurityAndApiTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private User opsUser;
    private User customerUser;
    private User merchantUser;
    private String opsToken;
    private String customerToken;
    private String merchantToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        opsUser = userRepository.save(new User(UUID.randomUUID(), "ops." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE));
        customerUser = userRepository.save(new User(UUID.randomUUID(), "cust." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));
        merchantUser = userRepository.save(new User(UUID.randomUUID(), "merch." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE));

        opsToken = jwtTokenService.generateAccessToken(opsUser);
        customerToken = jwtTokenService.generateAccessToken(customerUser);
        merchantToken = jwtTokenService.generateAccessToken(merchantUser);
    }

    @Test
    @DisplayName("Anonymous access to reconciliation endpoint returns 401 Unauthorized")
    void anonymousDenied401() throws Exception {
        mockMvc.perform(get("/api/reconciliation/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CUSTOMER role access to reconciliation endpoint returns 403 Forbidden")
    void customerDenied403() throws Exception {
        mockMvc.perform(get("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MERCHANT role access to reconciliation endpoint returns 403 Forbidden")
    void merchantDenied403() throws Exception {
        mockMvc.perform(get("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OPS role access to reconciliation endpoint returns 200 OK")
    void opsAllowed200() throws Exception {
        mockMvc.perform(get("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Bounded pagination rejects invalid negative or excessive page sizes with 400 ProblemDetail across all list endpoints")
    void boundedPaginationInvalidParams400() throws Exception {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        // 1. GET /api/reconciliation/runs
        mockMvc.perform(get("/api/reconciliation/runs?page=-1").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page index must not be negative")));

        mockMvc.perform(get("/api/reconciliation/runs?size=0").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page size must be at least 1")));

        mockMvc.perform(get("/api/reconciliation/runs?size=101").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page size must not exceed 100")));

        // 2. GET /api/reconciliation/runs/{runId}/items
        mockMvc.perform(get("/api/reconciliation/runs/" + runId + "/items?page=-1").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page index must not be negative")));

        mockMvc.perform(get("/api/reconciliation/runs/" + runId + "/items?size=0").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page size must be at least 1")));

        mockMvc.perform(get("/api/reconciliation/runs/" + runId + "/items?size=101").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page size must not exceed 100")));

        // 3. GET /api/reconciliation/cases
        mockMvc.perform(get("/api/reconciliation/cases?page=-1").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page index must not be negative")));

        mockMvc.perform(get("/api/reconciliation/cases?size=0").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page size must be at least 1")));

        mockMvc.perform(get("/api/reconciliation/cases?size=101").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("INVALID_RECONCILIATION_OPERATION")))
                .andExpect(jsonPath("$.detail", containsString("Page size must not exceed 100")));
    }

    @Test
    @DisplayName("Manual resolve on SNAPSHOT_MISMATCH case returns 409 Conflict with RECONCILIATION_CONFLICT without mutating case or snapshot")
    void manualResolveSnapshotMismatchReturns409Conflict() throws Exception {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                    "VALUES (?, NULL, 'PSP_CLEARING', 'INR', 'ACTIVE', NOW(), NOW())",
                accountId);

        // V3 auto-creates snapshot row with 0 balance upon ledger_accounts INSERT; update it to 10000
        jdbc.update("UPDATE ledger_balance_snapshots SET balance_minor = 10000 WHERE ledger_account_id = ?", accountId);

        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, expected_value, actual_value, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'SNAPSHOT_CONSISTENCY', 'SNAPSHOT_MISMATCH', 'LEDGER_ACCOUNT', ?, 20000, 10000, 'Mismatch', NOW())",
                itemId, runId, accountId);

        UUID caseId = jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);

        // Operator claims case
        mockMvc.perform(post("/api/reconciliation/cases/" + caseId + "/claim")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_REVIEW")));

        // Attempt manual resolve on SNAPSHOT_MISMATCH -> must be 409 Conflict
        mockMvc.perform(post("/api/reconciliation/cases/" + caseId + "/resolve")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNote\":\"Trying to manually close mismatch\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("RECONCILIATION_CONFLICT")))
                .andExpect(jsonPath("$.detail", containsString("SNAPSHOT_MISMATCH cannot be manually closed")));

        // Verify case remains IN_REVIEW (unchanged)
        var caseRow = jdbc.queryForMap("SELECT status, resolution_action, resolved_at FROM reconciliation_cases WHERE id = ?", caseId);
        org.assertj.core.api.Assertions.assertThat(caseRow.get("status")).isEqualTo("IN_REVIEW");
        org.assertj.core.api.Assertions.assertThat(caseRow.get("resolution_action")).isNull();
        org.assertj.core.api.Assertions.assertThat(caseRow.get("resolved_at")).isNull();

        // Verify snapshot remains unchanged (10000)
        var snapshotRow = jdbc.queryForMap("SELECT balance_minor FROM ledger_balance_snapshots WHERE ledger_account_id = ?", accountId);
        org.assertj.core.api.Assertions.assertThat(snapshotRow.get("balance_minor")).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Exact numeric representation: values beyond JavaScript safe integer range (>2^53) serialize as exact Strings")
    void exactStringNumericRepresentation() throws Exception {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        // 2^53 = 9007199254740992, 2^53 + 1 = 9007199254740993
        BigDecimal expectedBig = new BigDecimal("9007199254740992");
        BigDecimal actualBig = new BigDecimal("9007199254740993");

        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, expected_value, actual_value, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'SNAPSHOT_CONSISTENCY', 'SNAPSHOT_MISMATCH', 'LEDGER_ACCOUNT', ?, ?, ?, 'Large int mismatch', NOW())",
                itemId, runId, UUID.randomUUID(), expectedBig, actualBig);

        mockMvc.perform(get("/api/reconciliation/runs/" + runId + "/items")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].expectedValue", is("9007199254740992")))
                .andExpect(jsonPath("$.items[0].actualValue", is("9007199254740993")));
    }

    @Test
    @DisplayName("Claim, repair, and resolve API endpoints function end-to-end for OPS")
    void claimAndResolveApiEndpoints() throws Exception {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?, 'RUNNING', 'ON_DEMAND', NOW())", runId);

        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_items " +
                    "(id, reconciliation_run_id, classification, level, problem_type, entity_type, entity_id, description, detected_at) " +
                    "VALUES (?, ?, 'DISCREPANCY', 'JOURNAL_BALANCE', 'UNBALANCED_JOURNAL', 'JOURNAL_TRANSACTION', ?, 'Unbalanced journal', NOW())",
                itemId, runId, UUID.randomUUID());

        UUID caseId = jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);

        // 1. Claim case via REST
        mockMvc.perform(post("/api/reconciliation/cases/" + caseId + "/claim")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_REVIEW")))
                .andExpect(jsonPath("$.assignedToUserId", is(opsUser.getId().toString())));

        // 2. Resolve case via REST
        mockMvc.perform(post("/api/reconciliation/cases/" + caseId + "/resolve")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNote\":\"Investigated unbalanced journal issue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESOLVED")))
                .andExpect(jsonPath("$.resolutionAction", is("MANUAL_REVIEW_COMPLETED")))
                .andExpect(jsonPath("$.resolvedByUserId", is(opsUser.getId().toString())));
    }
}
