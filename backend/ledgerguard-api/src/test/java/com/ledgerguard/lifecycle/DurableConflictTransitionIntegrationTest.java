package com.ledgerguard.lifecycle;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.provider.application.ProviderConflictTransitionService;
import com.ledgerguard.provider.application.ProviderEventConflictException;
import com.ledgerguard.provider.application.ProviderEventIngressService;
import com.ledgerguard.provider.application.ProviderEventProcessingService;
import com.ledgerguard.provider.domain.ProviderEventPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableConflictTransitionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProviderConflictTransitionService providerConflictTransitionService;

    @Autowired
    private ProviderEventIngressService ingressService;

    @Autowired
    private ProviderEventProcessingService processingService;

    @Autowired
    private FundingOperationRepository fundingOperationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID customerAccountId;

    @BeforeEach
    void setUp() {
        Timestamp now = Timestamp.from(Instant.now());
        userId = UUID.randomUUID();
        customerAccountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                userId, "user-" + userId + "@example.com", now, now
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (id, owner_user_id, account_type, currency, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE', ?, ?)",
                customerAccountId, userId, now, now
        );
    }

    @Test
    @DisplayName("Conflicting provider identity on nonterminal operation durably commits RECONCILIATION_REQUIRED despite HTTP 409 exception")
    void conflictingProviderIdentityDurablyPersistsReconciliationRequired() {
        UUID fundingId = UUID.randomUUID();
        UUID boundProviderOpId = UUID.randomUUID();
        UUID conflictingProviderOpId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // 1. Create funding and bind boundProviderOpId in PROCESSING state
        jdbcTemplate.update(
                "INSERT INTO funding_operations (id, initiated_by_user_id, customer_ledger_account_id, amount_minor, currency, status, provider_operation_id, journal_transaction_id, created_at, completed_at, provider_poll_attempts, next_provider_poll_at, unknown_since) " +
                        "VALUES (?, ?, ?, 10000, 'INR', 'CREATED', NULL, NULL, ?, NULL, 0, NULL, NULL)",
                fundingId, userId, customerAccountId, now
        );
        jdbcTemplate.update(
                "UPDATE funding_operations SET status = 'PROCESSING', provider_operation_id = ?, next_provider_poll_at = ? WHERE id = ?",
                boundProviderOpId, now, fundingId
        );

        // 2. An event arrives for fundingId with conflictingProviderOpId
        ProviderEventPayload payload = new ProviderEventPayload(
                UUID.randomUUID(),
                1L,
                "PROVIDER_OPERATION_SUCCEEDED",
                conflictingProviderOpId,
                fundingId,
                "CREDIT",
                "SUCCEEDED",
                "10000",
                "INR",
                Instant.now(),
                "{}"
        );

        ingressService.recordEvent(payload);

        // 3. Process pending events for conflictingProviderOpId -> throws ProviderEventConflictException
        assertThatThrownBy(() -> processingService.processPendingEvents(conflictingProviderOpId))
                .isInstanceOf(ProviderEventConflictException.class);

        // 4. Assert that the business row in PostgreSQL durably transitioned to RECONCILIATION_REQUIRED
        FundingOperation funding = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(funding.getStatus()).isEqualTo(FundingStatus.RECONCILIATION_REQUIRED);
        assertThat(funding.getNextProviderPollAt()).isNull();
    }
}
