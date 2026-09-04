package com.ledgerguard.reconciliation;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.funding.infrastructure.PspTransportException;
import com.ledgerguard.reconciliation.application.ProviderSettlementChecker;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@DisplayName("ProviderSettlementCheckerIntegrationTest — Level 3 provider settlement tests")
class ProviderSettlementCheckerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProviderSettlementChecker providerSettlementChecker;
    @Autowired private ReconciliationItemRepository itemRepository;
    @Autowired private PspClient mockPspClient;

    private UUID runId;
    private UUID userId;
    private UUID customerAccountId;
    private UUID pspClearingAccountId;

    @TestConfiguration
    static class MockPspConfig {
        @Bean
        @Primary
        public PspClient pspClient() {
            return mock(PspClient.class);
        }
    }

    @BeforeEach
    void setUp() {
        reset(mockPspClient);
        runId = insertRunning();
        userId = insertUser();
        customerAccountId = insertAccount(userId, "CUSTOMER");
        pspClearingAccountId = ensurePspClearingAccount();
    }

    @Test
    @DisplayName("SUCCEEDED + provider SUCCEEDED with exact identity -> HEALTHY, no item")
    void succeededMatchesProviderProducesNoItem() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", providerOpId, 10000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "10000", "INR", "SUCCEEDED", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll()).noneMatch(i -> i.getEntityId().equals(fundingId));
    }

    @Test
    @DisplayName("SUCCEEDED + provider FAILED -> PROVIDER_STATUS_MISMATCH DISCREPANCY")
    void succeededWithProviderFailedProducesDiscrepancy() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", providerOpId, 5000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "5000", "INR", "FAILED", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_STATUS_MISMATCH
                        && i.getClassification() == ReconciliationClassification.DISCREPANCY);
    }

    @Test
    @DisplayName("Pre-acceptance FAILED (providerOpId NULL) + provider NOT_FOUND -> HEALTHY, no item")
    void preAcceptanceFailedWithNotFoundIsHealthy() {
        UUID fundingId = UUID.randomUUID();
        insertFunding(fundingId, "FAILED", null, 4000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(Optional.empty());

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll()).noneMatch(i -> i.getEntityId().equals(fundingId));
    }

    @Test
    @DisplayName("Pre-acceptance FAILED (providerOpId NULL) + provider SUCCEEDED -> SERIOUS DISCREPANCY")
    void preAcceptanceFailedWithProviderSucceededIsSeriousDiscrepancy() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "FAILED", null, 4000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "4000", "INR", "SUCCEEDED", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_STATUS_MISMATCH
                        && i.getClassification() == ReconciliationClassification.DISCREPANCY
                        && i.getDescription().contains("SERIOUS DISCREPANCY"));
    }

    @Test
    @DisplayName("Post-acceptance FAILED (providerOpId NON-NULL) + matching FAILED -> HEALTHY")
    void postAcceptanceFailedWithMatchingFailedIsHealthy() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "FAILED", providerOpId, 4000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "4000", "INR", "FAILED", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll()).noneMatch(i -> i.getEntityId().equals(fundingId));
    }

    @Test
    @DisplayName("Post-acceptance FAILED (providerOpId NON-NULL) + NOT_FOUND -> PROVIDER_NOT_FOUND DISCREPANCY")
    void postAcceptanceFailedWithNotFoundIsDiscrepancy() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "FAILED", providerOpId, 4000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(Optional.empty());

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_NOT_FOUND
                        && i.getClassification() == ReconciliationClassification.DISCREPANCY);
    }

    @Test
    @DisplayName("PROCESSING + provider PROCESSING with matching identity -> HEALTHY, in-flight")
    void processingWithProviderProcessingIsHealthy() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "PROCESSING", providerOpId, 3000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "3000", "INR", "PROCESSING", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll()).noneMatch(i -> i.getEntityId().equals(fundingId));
    }

    @Test
    @DisplayName("Un-cleared funding: local PROCESSING + provider SUCCEEDED -> UN-CLEARED DISCREPANCY")
    void unclearedFundingDetectedAsDiscrepancy() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "PROCESSING", providerOpId, 3000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "3000", "INR", "SUCCEEDED", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_STATUS_MISMATCH
                        && i.getDescription().contains("UN-CLEARED"));

        // No local mutation — local row still PROCESSING
        String status = jdbc.queryForObject("SELECT status FROM funding_operations WHERE id = ?", String.class, fundingId);
        assertThat(status).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("RECONCILIATION_REQUIRED + provider SUCCEEDED -> DISCREPANCY observation, local status unchanged")
    void reconciliationRequiredWithProviderSucceededProducesDiscrepancyWithoutMutation() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "RECONCILIATION_REQUIRED", providerOpId, 8000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "8000", "INR", "SUCCEEDED", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_STATUS_MISMATCH);

        // Local status stays RECONCILIATION_REQUIRED
        String status = jdbc.queryForObject("SELECT status FROM funding_operations WHERE id = ?", String.class, fundingId);
        assertThat(status).isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    @DisplayName("Provider identity mismatch: wrong operationType -> PROVIDER_IDENTITY_MISMATCH")
    void identityMismatchWrongOpType() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", providerOpId, 1000L);

        // Funding expects CREDIT, mock returns DEBIT
        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "DEBIT", "1000", "INR", "SUCCEEDED", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_IDENTITY_MISMATCH);
    }

    @Test
    @DisplayName("Provider transport timeout -> PROVIDER_UNAVAILABLE UNRESOLVED item")
    void transportTimeoutProducesProviderUnavailable() {
        UUID fundingId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", UUID.randomUUID(), 1000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId))
                .thenThrow(new PspTransportException("I/O timeout", new java.io.IOException("Connection timed out")));

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_UNAVAILABLE
                        && i.getClassification() == ReconciliationClassification.UNRESOLVED);
    }

    @Test
    @DisplayName("Provider GET boundary: TransactionSynchronizationManager has no active transaction and no DB lock held during GET")
    void providerGetExecutesOutsideDatabaseTransaction() {
        UUID fundingId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", UUID.randomUUID(), 1000L);

        AtomicBoolean wasTxActive = new AtomicBoolean(true);
        AtomicBoolean lockAcquiredDuringGet = new AtomicBoolean(false);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenAnswer(inv -> {
            wasTxActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            // Verify no row lock is held on funding_operations by attempting a concurrent row lock
            try {
                jdbc.queryForObject("SELECT status FROM funding_operations WHERE id = ? FOR UPDATE NOWAIT", String.class, fundingId);
                lockAcquiredDuringGet.set(true);
            } catch (Exception e) {
                lockAcquiredDuringGet.set(false);
            }
            return Optional.empty();
        });

        providerSettlementChecker.check(runId);

        assertThat(wasTxActive.get()).isFalse();
        assertThat(lockAcquiredDuringGet.get()).isTrue(); // Lock succeeded because no lock was held by the checker
    }

    @Test
    @DisplayName("Provider HTTP 200 with null body throws PspProtocolException -> classified as UNRESOLVED / PROVIDER_UNAVAILABLE")
    void providerNullBodyTreatedAsProviderUnavailable() {
        UUID fundingId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", UUID.randomUUID(), 1000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId))
                .thenThrow(new com.ledgerguard.funding.infrastructure.PspProtocolException("Provider returned empty body with 200 OK", 200));

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_UNAVAILABLE
                        && i.getClassification() == ReconciliationClassification.UNRESOLVED);
    }

    @Test
    @DisplayName("Provider malformed JSON / conversion failure throws PspProtocolException -> classified as UNRESOLVED / PROVIDER_UNAVAILABLE")
    void providerMalformedJsonTreatedAsProviderUnavailable() {
        UUID fundingId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", UUID.randomUUID(), 1000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId))
                .thenThrow(new com.ledgerguard.funding.infrastructure.PspProtocolException("Malformed response", new RuntimeException("Unparseable JSON"), 200));

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_UNAVAILABLE
                        && i.getClassification() == ReconciliationClassification.UNRESOLVED);
    }

    @Test
    @DisplayName("Provider unsupported / unknown status (e.g. PENDING_X) -> classified as UNRESOLVED / PROVIDER_UNAVAILABLE")
    void providerUnsupportedStatusTreatedAsProviderUnavailable() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", providerOpId, 1000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "1000", "INR", "PENDING_X", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_UNAVAILABLE
                        && i.getClassification() == ReconciliationClassification.UNRESOLVED
                        && i.getDescription().contains("unsupported, blank, or null status"));
    }

    @Test
    @DisplayName("Provider blank status -> classified as UNRESOLVED / PROVIDER_UNAVAILABLE")
    void providerBlankStatusTreatedAsProviderUnavailable() {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        insertFunding(fundingId, "SUCCEEDED", providerOpId, 1000L);

        when(mockPspClient.getOperationByClientOperationId(fundingId)).thenReturn(
                Optional.of(new PspOperationResponse(providerOpId, fundingId, "CREDIT", "1000", "INR", "   ", null, null, false))
        );

        providerSettlementChecker.check(runId);

        assertThat(itemRepository.findAll())
                .anyMatch(i -> i.getEntityId().equals(fundingId)
                        && i.getProblemType() == ReconciliationProblemType.PROVIDER_UNAVAILABLE
                        && i.getClassification() == ReconciliationClassification.UNRESOLVED);
    }

    private void insertFunding(UUID id, String status, UUID providerOpId, long amountMinor) {
        Timestamp now = Timestamp.from(Instant.now());

        // 1. Initial insert as CREATED
        jdbc.update("INSERT INTO funding_operations " +
                    "(id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                    "VALUES (?, ?, ?, ?, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                id, userId, customerAccountId, amountMinor, now);

        if ("CREATED".equals(status)) {
            return;
        }

        // If pre-acceptance FAILED (providerOpId is null)
        if ("FAILED".equals(status) && providerOpId == null) {
            jdbc.update("UPDATE funding_operations SET status = 'FAILED', completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                    now, id);
            return;
        }

        // Move to PROCESSING first
        jdbc.update("UPDATE funding_operations SET status = 'PROCESSING', next_provider_poll_at = ? WHERE id = ?",
                now, id);

        if ("PROCESSING".equals(status)) {
            if (providerOpId != null) {
                jdbc.update("UPDATE funding_operations SET provider_operation_id = ? WHERE id = ?", providerOpId, id);
            }
            return;
        }

        if ("UNKNOWN".equals(status)) {
            jdbc.update("UPDATE funding_operations SET status = 'UNKNOWN', unknown_since = ?, next_provider_poll_at = ?, provider_operation_id = ? WHERE id = ?",
                    now, now, providerOpId, id);
            return;
        }

        if ("RECONCILIATION_REQUIRED".equals(status)) {
            jdbc.update("UPDATE funding_operations SET status = 'RECONCILIATION_REQUIRED', next_provider_poll_at = NULL, provider_operation_id = ? WHERE id = ?",
                    providerOpId, id);
            return;
        }

        if ("FAILED".equals(status)) {
            // Failing from PROCESSING with providerOpId
            jdbc.update("UPDATE funding_operations SET status = 'FAILED', provider_operation_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                    providerOpId, now, id);
            return;
        }

        if ("SUCCEEDED".equals(status)) {
            // Need a valid settlement journal
            UUID journalId = UUID.randomUUID();
            jdbc.update("INSERT INTO journal_transactions (id, status, currency, created_at) VALUES (?, 'DRAFT', 'INR', ?)",
                    journalId, now);
            jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'DEBIT', ?)",
                    UUID.randomUUID(), journalId, pspClearingAccountId, amountMinor);
            jdbc.update("INSERT INTO journal_entries (id, journal_transaction_id, ledger_account_id, direction, amount_minor) VALUES (?, ?, ?, 'CREDIT', ?)",
                    UUID.randomUUID(), journalId, customerAccountId, amountMinor);
            jdbc.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = ? WHERE id = ?",
                    now, journalId);

            jdbc.update("UPDATE funding_operations SET status = 'SUCCEEDED', provider_operation_id = ?, journal_transaction_id = ?, completed_at = ?, next_provider_poll_at = NULL WHERE id = ?",
                    providerOpId, journalId, now, id);
        }
    }

    private UUID insertRunning() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, status, trigger_source, started_at) VALUES (?,?,?,?)",
                id, "RUNNING", "ON_DEMAND", Timestamp.from(Instant.now()));
        return id;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                id, "recon-l3-" + id + "@example.com", "hash", "CUSTOMER", "ACTIVE", now, now);
        return id;
    }

    private UUID insertAccount(UUID uId, String accountType) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        UUID ownerId = ("CUSTOMER".equals(accountType) || "MERCHANT".equals(accountType)) ? uId : null;
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
