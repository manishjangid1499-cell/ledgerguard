package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationCaseStatus;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Reconciliation summary endpoint, case filtering (runId, assigned), and run trigger authorization")
class ReconciliationSummaryAndFilterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private com.ledgerguard.reconciliation.infrastructure.ReconciliationRunRepository runRepository;

    @Autowired
    private com.ledgerguard.reconciliation.infrastructure.ReconciliationCaseRepository caseRepository;

    @Autowired
    private com.ledgerguard.reconciliation.application.ReconciliationCaseManagementService managementService;

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
    @DisplayName("GET /api/reconciliation/summary returns valid contract values matching repository counts")
    void summaryContractValidation() throws Exception {
        long expectedOpen = caseRepository.countByStatus(ReconciliationCaseStatus.OPEN);
        long expectedInReview = caseRepository.countByStatus(ReconciliationCaseStatus.IN_REVIEW);
        long expectedResolved = caseRepository.countByStatus(ReconciliationCaseStatus.RESOLVED);
        long expectedTotalCases = caseRepository.count();
        long expectedTotalRuns = runRepository.count();

        mockMvc.perform(get("/api/reconciliation/summary")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openCases", is((int) expectedOpen)))
                .andExpect(jsonPath("$.inReviewCases", is((int) expectedInReview)))
                .andExpect(jsonPath("$.resolvedCases", is((int) expectedResolved)))
                .andExpect(jsonPath("$.totalCases", is((int) expectedTotalCases)))
                .andExpect(jsonPath("$.totalRuns", is((int) expectedTotalRuns)));
    }

    @Test
    @DisplayName("GET /api/reconciliation/summary returns accurate case status counts and latest run details")
    void summaryWithRunsAndCases() throws Exception {
        long initialOpenCases = caseRepository.countByStatus(ReconciliationCaseStatus.OPEN);
        long initialInReviewCases = caseRepository.countByStatus(ReconciliationCaseStatus.IN_REVIEW);
        long initialResolvedCases = caseRepository.countByStatus(ReconciliationCaseStatus.RESOLVED);
        long initialTotalCases = caseRepository.count();
        long initialTotalRuns = runRepository.count();

        UUID olderRunId = UUID.randomUUID();
        UUID latestRunId = UUID.randomUUID();
        Instant t1 = Instant.now().plusSeconds(10);
        Instant t2 = Instant.now().plusSeconds(20);

        insertRun(olderRunId, "RUNNING", "SCHEDULED", t1, null);
        UUID item3 = insertItem(olderRunId, ReconciliationClassification.DISCREPANCY, ReconciliationLevel.JOURNAL_BALANCE, ReconciliationProblemType.MALFORMED_JOURNAL);
        UUID item4 = insertItem(olderRunId, ReconciliationClassification.DISCREPANCY, ReconciliationLevel.JOURNAL_BALANCE, ReconciliationProblemType.UNBALANCED_JOURNAL);
        completeRun(olderRunId, t1.plusSeconds(5));

        insertRun(latestRunId, "RUNNING", "ON_DEMAND", t2, null);
        UUID item1 = insertItem(latestRunId, ReconciliationClassification.DISCREPANCY, ReconciliationLevel.SNAPSHOT_CONSISTENCY, ReconciliationProblemType.SNAPSHOT_MISMATCH);
        UUID item2 = insertItem(latestRunId, ReconciliationClassification.UNRESOLVED, ReconciliationLevel.PROVIDER_SETTLEMENT, ReconciliationProblemType.PROVIDER_STILL_PROCESSING);
        completeRun(latestRunId, t2.plusSeconds(5));

        configureCase(item1, ReconciliationCaseStatus.OPEN, null);
        configureCase(item2, ReconciliationCaseStatus.IN_REVIEW, opsUser.getId());
        configureCase(item3, ReconciliationCaseStatus.RESOLVED, opsUser.getId());
        configureCase(item4, ReconciliationCaseStatus.OPEN, null);

        mockMvc.perform(get("/api/reconciliation/summary")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openCases", is((int) (initialOpenCases + 2))))
                .andExpect(jsonPath("$.inReviewCases", is((int) (initialInReviewCases + 1))))
                .andExpect(jsonPath("$.resolvedCases", is((int) (initialResolvedCases + 1))))
                .andExpect(jsonPath("$.totalCases", is((int) (initialTotalCases + 4))))
                .andExpect(jsonPath("$.totalRuns", is((int) (initialTotalRuns + 2))))
                .andExpect(jsonPath("$.latestRun.id", is(latestRunId.toString())))
                .andExpect(jsonPath("$.latestRun.triggerSource", is("ON_DEMAND")))
                .andExpect(jsonPath("$.latestRun.status", is("COMPLETED")));
    }

    @Test
    @DisplayName("GET /api/reconciliation/summary authorization: 401 for anonymous, 403 for Customer and Merchant")
    void summaryAuthorizationRules() throws Exception {
        mockMvc.perform(get("/api/reconciliation/summary"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/reconciliation/summary")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reconciliation/summary")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/reconciliation/cases filters correctly by runId and assigned")
    void casesFilteringByRunIdAndAssigned() throws Exception {
        UUID run1 = UUID.randomUUID();
        UUID run2 = UUID.randomUUID();
        Instant now = Instant.now();
        insertRun(run1, "RUNNING", "ON_DEMAND", now.minusSeconds(60), null);
        UUID item1 = insertItem(run1, ReconciliationClassification.DISCREPANCY, ReconciliationLevel.SNAPSHOT_CONSISTENCY, ReconciliationProblemType.SNAPSHOT_MISMATCH);
        UUID item2 = insertItem(run1, ReconciliationClassification.UNRESOLVED, ReconciliationLevel.PROVIDER_SETTLEMENT, ReconciliationProblemType.PROVIDER_STILL_PROCESSING);
        completeRun(run1, now.minusSeconds(50));

        insertRun(run2, "RUNNING", "ON_DEMAND", now.minusSeconds(30), null);
        UUID item3 = insertItem(run2, ReconciliationClassification.DISCREPANCY, ReconciliationLevel.JOURNAL_BALANCE, ReconciliationProblemType.UNBALANCED_JOURNAL);
        completeRun(run2, now.minusSeconds(20));

        UUID case1 = configureCase(item1, ReconciliationCaseStatus.OPEN, null);
        UUID case2 = configureCase(item2, ReconciliationCaseStatus.IN_REVIEW, opsUser.getId());
        UUID case3 = configureCase(item3, ReconciliationCaseStatus.OPEN, null);

        long expectedAssigned = caseRepository.findAll().stream().filter(c -> c.getAssignedToUserId() != null).count();
        long expectedUnassigned = caseRepository.findAll().stream().filter(c -> c.getAssignedToUserId() == null).count();

        // Filter by run1 -> should return case1 and case2
        mockMvc.perform(get("/api/reconciliation/cases?runId=" + run1)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.items", hasSize(2)));

        // Filter by run2 -> should return case3
        mockMvc.perform(get("/api/reconciliation/cases?runId=" + run2)
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(case3.toString())));

        // Filter by assigned=true -> should return expectedAssigned
        mockMvc.perform(get("/api/reconciliation/cases?assigned=true")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is((int) expectedAssigned)));

        // Filter by assigned=false -> should return expectedUnassigned
        mockMvc.perform(get("/api/reconciliation/cases?assigned=false")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is((int) expectedUnassigned)));

        // Filter by run1 AND assigned=true -> should return only case2
        mockMvc.perform(get("/api/reconciliation/cases?runId=" + run1 + "&assigned=true")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(case2.toString())));

        // Filter by run1 AND assigned=false -> should return only case1
        mockMvc.perform(get("/api/reconciliation/cases?runId=" + run1 + "&assigned=false")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(case1.toString())));

        // Filter by run2 AND assigned=true -> should return 0
        mockMvc.perform(get("/api/reconciliation/cases?runId=" + run2 + "&assigned=true")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    @DisplayName("POST /api/reconciliation/runs triggers run and returns 201 Created with authoritative summary")
    void triggerRunSuccessAndAuthorization() throws Exception {
        // Customer denied 403
        mockMvc.perform(post("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        // Merchant denied 403
        mockMvc.perform(post("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden());

        // Anonymous denied 401
        mockMvc.perform(post("/api/reconciliation/runs"))
                .andExpect(status().isUnauthorized());

        // OPS allowed -> 201 Created
        mockMvc.perform(post("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.triggerSource", is("ON_DEMAND")))
                .andExpect(jsonPath("$.startedAt", notNullValue()))
                .andExpect(jsonPath("$.completedAt", notNullValue()));
    }

    @Autowired
    private com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository itemRepository;

    private void insertRun(UUID id, String status, String triggerSource, Instant startedAt, Instant completedAt) {
        jdbc.update("""
                INSERT INTO reconciliation_runs (
                    id, status, trigger_source, started_at, completed_at,
                    journals_checked, accounts_checked, operations_checked,
                    discrepancy_count, unresolved_count, failure_reason
                ) VALUES (?, 'RUNNING', ?, ?, NULL, 0, 0, 0, 0, 0, NULL)
                """, id, triggerSource, Timestamp.from(startedAt));
    }

    private void completeRun(UUID runId, Instant completedAt) {
        jdbc.update("""
                UPDATE reconciliation_runs
                SET status = 'COMPLETED', completed_at = ?
                WHERE id = ?
                """, Timestamp.from(completedAt), runId);
    }

    private UUID insertItem(UUID runId, ReconciliationClassification classification, ReconciliationLevel level, ReconciliationProblemType problemType) {
        String entityType = switch (level) {
            case JOURNAL_BALANCE -> "JOURNAL_TRANSACTION";
            case SNAPSHOT_CONSISTENCY -> "LEDGER_ACCOUNT";
            case PROVIDER_SETTLEMENT -> "FUNDING_OPERATION";
        };
        com.ledgerguard.reconciliation.domain.ReconciliationItem item = com.ledgerguard.reconciliation.domain.ReconciliationItem.builder()
                .runId(runId)
                .classification(classification)
                .level(level)
                .problemType(problemType)
                .entityType(entityType)
                .entityId(UUID.randomUUID())
                .observedLocalStatus("POSTED")
                .description("Test description")
                .build();
        return itemRepository.save(item).getId();
    }

    private UUID configureCase(UUID itemId, ReconciliationCaseStatus status, UUID assignedToUserId) {
        UUID caseId = jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?", UUID.class, itemId);
        if (status == ReconciliationCaseStatus.IN_REVIEW) {
            managementService.claimCase(caseId, assignedToUserId);
        } else if (status == ReconciliationCaseStatus.RESOLVED) {
            managementService.claimCase(caseId, assignedToUserId);
            managementService.resolveManually(caseId, assignedToUserId, "Investigation confirmed and resolved");
        }
        return caseId;
    }
}
