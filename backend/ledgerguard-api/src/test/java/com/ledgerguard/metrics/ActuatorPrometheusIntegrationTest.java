package com.ledgerguard.metrics;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.idempotency.application.IdempotencyCommand;
import com.ledgerguard.idempotency.application.IdempotencyExecutionResult;
import com.ledgerguard.idempotency.application.IdempotencyService;
import com.ledgerguard.idempotency.domain.IdempotencyConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DisplayName("ActuatorPrometheusIntegrationTest â€” Comprehensive scraping & live reflection")
class ActuatorPrometheusIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class SpyConfig {
        @Bean
        @Primary
        public IntegrityMetricsSnapshotReader snapshotReaderSpy(JdbcTemplate jdbcTemplate) {
            return Mockito.spy(new IntegrityMetricsSnapshotReader(jdbcTemplate));
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IntegrityMetricsSnapshotReader snapshotReaderSpy;

    @Autowired
    private IntegrityMetricsSampler sampler;

    @Autowired
    private IntegrityMetrics metrics;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private MeterRegistry meterRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void tearDown() {
        safeEnableTriggers();
    }

    private void safeEnableTriggers() {
        try {
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        } catch (Exception ignored) {}
        try {
            jdbc.execute("ALTER TABLE journal_transactions ENABLE TRIGGER trg_journal_transactions_balance_check");
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("Endpoint accessibility: GET /actuator/prometheus is permitAll and has security headers")
    void endpointAccessibilityAndHeaders() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    @DisplayName("No DB work on scrape: 20 scrapes do not invoke snapshotReader")
    void noDbWorkOnScrape() throws Exception {
        Mockito.clearInvocations(snapshotReaderSpy);

        // 1. Explicit synchronous sample
        sampler.sampleNow();
        verify(snapshotReaderSpy, times(1)).readSnapshot();

        // 2. Perform 20 scrapes
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk());
        }

        // 3. Reader invocation count must remain strictly 1
        verify(snapshotReaderSpy, times(1)).readSnapshot();
    }

    @Test
    @DisplayName("Rate-limit exemption: 15 rapid scrapes succeed without 429")
    void rateLimitExemption() throws Exception {
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Cardinality and absence of PII: zero UUIDs, zero idempotency keys in custom metrics")
    void cardinalityAndAbsenceOfPii() throws Exception {
        // Meter tags
        assertThat(meterRegistry.get("unbalanced_journal_count").gauge().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("reconciliation_discrepancies").gauge().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("outbox_lag_seconds").gauge().getId().getTags()).isEmpty();

        var counters = meterRegistry.find("duplicate_idempotency_keys").counters();
        assertThat(counters).hasSize(3);
        for (var c : counters) {
            List<Tag> tags = c.getId().getTags();
            assertThat(tags).hasSize(1);
            assertThat(tags.get(0).getKey()).isEqualTo("reason");
            assertThat(tags.get(0).getValue()).isIn("replay", "fingerprint_conflict", "in_progress");
        }

        // Scrape body inspection
        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("unbalanced_journal_count");
        assertThat(body).contains("reconciliation_discrepancies");
        assertThat(body).contains("outbox_lag_seconds");
        assertThat(body).contains("duplicate_idempotency_keys_total");

        assertThat(body).doesNotContain("unbalanced_journal_count_count");
        assertThat(body).doesNotContain("outbox_lag_seconds_seconds");
        assertThat(body).doesNotContain("duplicate_idempotency_keys_count_total");
    }

    @Test
    @DisplayName("Live reflection: unbalanced_journal_count tracks corruption and returns to 0 on cleanup")
    void liveReflectionUnbalancedJournals() throws Exception {
        // Baseline sample
        sampler.sampleNow();
        MvcResult res0 = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(extractMetricValue(res0.getResponse().getContentAsString(), "unbalanced_journal_count")).isEqualTo(0.0);

        // Insert valid balanced posted journal
        UUID journalId = insertPostedJournal(10000L);

        // Test-only: disable immutability trigger, corrupt one entry amount by +99, re-enable
        jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_immutability");
        try {
            jdbc.update("UPDATE journal_entries SET amount_minor = amount_minor + 99 " +
                        "WHERE journal_transaction_id = ? AND direction = 'CREDIT'", journalId);
        } finally {
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        }

        // Sample and verify metric reflects 1.0
        sampler.sampleNow();
        MvcResult res1 = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(extractMetricValue(res1.getResponse().getContentAsString(), "unbalanced_journal_count")).isEqualTo(1.0);

        // Cleanup: restore entry amount by -99
        jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_immutability");
        try {
            jdbc.update("UPDATE journal_entries SET amount_minor = amount_minor - 99 " +
                        "WHERE journal_transaction_id = ? AND direction = 'CREDIT'", journalId);
        } finally {
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_immutability");
        }

        // Sample and verify returns to 0.0
        sampler.sampleNow();
        MvcResult res2 = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(extractMetricValue(res2.getResponse().getContentAsString(), "unbalanced_journal_count")).isEqualTo(0.0);

        // Verify trigger is enabled
        String tgenabled = jdbc.queryForObject(
                "SELECT tgenabled FROM pg_trigger WHERE tgname = 'trg_journal_entries_immutability'", String.class);
        assertThat(tgenabled).isEqualTo("O");
    }

    @Test
    @DisplayName("Live reflection: reconciliation_discrepancies tracks active cases and clears on resolution")
    void liveReflectionReconciliationDiscrepancies() throws Exception {
        UUID userId = insertUser();
        // Resolve any pre-existing open cases from previous tests to ensure isolated clean baseline
        jdbc.update("""
                UPDATE reconciliation_cases
                SET status = 'RESOLVED',
                    resolved_by_user_id = ?,
                    resolved_at = NOW(),
                    resolution_action = 'ALREADY_CONSISTENT',
                    updated_at = NOW()
                WHERE status IN ('OPEN', 'IN_REVIEW')
                """, userId);

        UUID runId = insertReconRun();
        UUID itemId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Insert item with classification DISCREPANCY (V15 trigger auto-creates OPEN case)
        jdbc.update("""
                INSERT INTO reconciliation_items (id, reconciliation_run_id, classification, level, problem_type,
                    entity_type, entity_id, description, detected_at)
                VALUES (?, ?, 'DISCREPANCY', 'JOURNAL_BALANCE', 'UNBALANCED_JOURNAL', 'JOURNAL_TRANSACTION', ?, 'Test discrepancy', ?)
                """, itemId, runId, UUID.randomUUID(), now);

        // Find created case
        UUID caseId = jdbc.queryForObject("SELECT id FROM reconciliation_cases WHERE reconciliation_item_id = ?",
                UUID.class, itemId);
        assertThat(caseId).isNotNull();

        // Sample and verify count reflects 1.0
        sampler.sampleNow();
        MvcResult res1 = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(extractMetricValue(res1.getResponse().getContentAsString(), "reconciliation_discrepancies")).isEqualTo(1.0);

        // Resolve case
        jdbc.update("""
                UPDATE reconciliation_cases
                SET status = 'RESOLVED',
                    resolved_by_user_id = ?,
                    resolved_at = ?,
                    resolution_action = 'ALREADY_CONSISTENT',
                    updated_at = ?
                WHERE id = ?
                """, userId, now, now, caseId);

        // Sample and verify cleared back to 0.0
        sampler.sampleNow();
        MvcResult res2 = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(extractMetricValue(res2.getResponse().getContentAsString(), "reconciliation_discrepancies")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Live reflection: outbox_lag_seconds reflects oldest PENDING and returns to 0 on publish")
    void liveReflectionOutboxLag() throws Exception {
        // Mark any pre-existing pending events published so backlog starts at 0
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHED', published_at = NOW() WHERE status = 'PENDING'");

        sampler.sampleNow();
        MvcResult res0 = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(extractMetricValue(res0.getResponse().getContentAsString(), "outbox_lag_seconds")).isEqualTo(0.0);

        UUID eventId = UUID.randomUUID();
        UUID aggId = UUID.randomUUID();
        // Insert PENDING outbox row created 35 seconds ago
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version,
                    payload, status, occurred_at, created_at)
                VALUES (?, 'TRANSFER', ?, 'TransferCompleted', 1, '{"status":"COMPLETED"}'::jsonb,
                    'PENDING', NOW() - INTERVAL '35 seconds', NOW() - INTERVAL '35 seconds')
                """, eventId, aggId);

        sampler.sampleNow();
        MvcResult res1 = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        double lag = extractMetricValue(res1.getResponse().getContentAsString(), "outbox_lag_seconds");
        assertThat(lag).isGreaterThanOrEqualTo(30.0);

        // Mark published
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHED', published_at = NOW() WHERE id = ?", eventId);

        sampler.sampleNow();
        MvcResult res2 = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(extractMetricValue(res2.getResponse().getContentAsString(), "outbox_lag_seconds")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Live reflection: duplicate_idempotency_keys increments on duplicate encounters without leaking keys")
    void liveReflectionDuplicateIdempotencyKeys() throws Exception {
        UUID userId = insertUser();
        String operation = "TEST_TRANSFER";
        String idempotencyKey = "DO_NOT_EXPOSE_IDEMPOTENCY_KEY_" + UUID.randomUUID();
        String fingerprint = "a".repeat(64);

        IdempotencyCommand cmd = new IdempotencyCommand(userId, operation, idempotencyKey, fingerprint);

        // 1. Initial request -> executed, no duplicate counter increment
        IdempotencyExecutionResult res1 = idempotencyService.execute(cmd, UUID::randomUUID);
        assertThat(res1.replayed()).isFalse();

        // 2. Replay with same fingerprint -> replayed, increment replay counter
        IdempotencyExecutionResult res2 = idempotencyService.execute(cmd, UUID::randomUUID);
        assertThat(res2.replayed()).isTrue();
        assertThat(res2.resultId()).isEqualTo(res1.resultId());

        // 3. Duplicate key with different fingerprint -> throws IdempotencyConflictException, increment conflict counter
        IdempotencyCommand conflictCmd = new IdempotencyCommand(userId, operation, idempotencyKey, "b".repeat(64));
        assertThatThrownBy(() -> idempotencyService.execute(conflictCmd, UUID::randomUUID))
                .isInstanceOf(IdempotencyConflictException.class);

        // 4. Scrape and verify
        MvcResult scrape = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn();
        String body = scrape.getResponse().getContentAsString();

        assertThat(body).contains("duplicate_idempotency_keys_total{reason=\"replay\"}");
        assertThat(body).contains("duplicate_idempotency_keys_total{reason=\"fingerprint_conflict\"}");
        assertThat(body).contains("duplicate_idempotency_keys_total{reason=\"in_progress\"}");

        // Assert sentinel key is absent from the entire custom metric output
        assertThat(body).doesNotContain("DO_NOT_EXPOSE_IDEMPOTENCY_KEY");
    }

    private double extractMetricValue(String scrapeBody, String metricName) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(metricName) + "(?:\\{[^}]*\\})?\\s+([0-9.NaN+-]+(?:[eE][+-]?[0-9]+)?)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(scrapeBody);
        if (matcher.find()) {
            String valStr = matcher.group(1);
            if ("NaN".equals(valStr)) return Double.NaN;
            return Double.parseDouble(valStr);
        }
        throw new AssertionError("Metric " + metricName + " not found in scrape output:\n" + scrapeBody);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                id, "metrics-user-" + id + "@example.com", "hash", "CUSTOMER", "ACTIVE", now, now);
        return id;
    }

    private UUID insertReconRun() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) " +
                    "VALUES (?,?,?,?)",
                id, "RUNNING", "ON_DEMAND", Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertPostedJournal(long amountMinor) {
        UUID userId = insertUser();
        UUID customerAccId = insertAccount(userId, "CUSTOMER");
        UUID pspAccId = ensurePspClearingAccount();

        UUID journalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?,?,?,?)",
                journalId, "DRAFT", "INR", now);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journalId, pspAccId, "DEBIT", amountMinor);
        jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journalId, customerAccId, "CREDIT", amountMinor);

        jdbc.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                now, journalId);

        return journalId;
    }

    private UUID insertAccount(UUID userId, String accountType) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        UUID ownerId = ("CUSTOMER".equals(accountType) || "MERCHANT".equals(accountType)) ? userId : null;
        jdbc.update("INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                id, ownerId, accountType, "INR", "ACTIVE", now, now);
        return id;
    }

    private UUID ensurePspClearingAccount() {
        UUID existing = jdbc.query(
                "SELECT id FROM ledger_accounts WHERE account_type = 'PSP_CLEARING' AND status = 'ACTIVE' LIMIT 1",
                rs -> rs.next() ? UUID.fromString(rs.getString("id")) : null);
        if (existing != null) return existing;

        return insertAccount(null, "PSP_CLEARING");
    }
}