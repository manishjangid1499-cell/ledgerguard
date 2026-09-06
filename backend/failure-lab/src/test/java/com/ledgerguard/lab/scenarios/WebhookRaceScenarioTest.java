package com.ledgerguard.lab.scenarios;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.AbstractFailureLabIntegrationTest;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Scenario 4 — Webhook Race (Real ProviderWebhookController & Deduplication)")
class WebhookRaceScenarioTest extends AbstractFailureLabIntegrationTest {

    @Test
    @DisplayName("WEBHOOK_RACE: 5 concurrent duplicate signed webhooks delivered to ProviderWebhookController yield exactly 1 settlement and 4 safe deduplications")
    void testWebhookRaceRealProductionPath() throws Exception {
        // 1. Setup real user, customer wallet account, and PSP clearing account
        UUID userId = UUID.randomUUID();
        userRepository.save(new User(userId, "webhookUser." + userId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        LedgerAccount customerAccount = ledgerAccountRepository.save(LedgerAccount.createCustomerAccount(userId));
        ledgerAccountRepository.findByAccountType(AccountType.PSP_CLEARING)
                .orElseGet(() -> ledgerAccountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));

        // 2. Create in-flight FundingOperation in PROCESSING status
        UUID fundingId = UUID.randomUUID();
        long fundingAmount = 15000L; // INR 150.00
        FundingOperation fundingOp = new FundingOperation(
                fundingId,
                userId,
                customerAccount.getId(),
                fundingAmount,
                "INR",
                Instant.now()
        );
        fundingOperationRepository.saveAndFlush(fundingOp);
        fundingOp.prepareSubmission(Instant.now().plusSeconds(60));
        fundingOperationRepository.saveAndFlush(fundingOp);

        // 3. Construct signed webhook payload matching production schema
        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        String timestampHeader = String.valueOf(Instant.now().getEpochSecond());
        String occurredAtIso = Instant.now().toString();

        String payloadJson = String.format(
                "{\"eventId\":\"%s\",\"eventSequence\":1,\"eventType\":\"PROVIDER_OPERATION_SUCCEEDED\",\"providerOperationId\":\"%s\",\"clientOperationId\":\"%s\",\"operationType\":\"CREDIT\",\"status\":\"SUCCEEDED\",\"amountMinor\":\"%d\",\"currency\":\"INR\",\"occurredAt\":\"%s\"}",
                eventId, providerOpId, fundingId, fundingAmount, occurredAtIso
        );
        byte[] rawBody = payloadJson.getBytes(StandardCharsets.UTF_8);
        String signatureHeader = computeHmacSignature(timestampHeader, rawBody, RUNTIME_WEBHOOK_SECRET);

        // 4. Dispatch 5 concurrent duplicate deliveries of the SAME provider event to ProviderWebhookController
        int concurrency = 5;
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        List<Future<ResponseEntity<Map<String, String>>>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                // Real production entry point: ProviderWebhookController with real HMAC validation and dedup
                return webhookController.receiveWebhook(timestampHeader, signatureHeader, rawBody);
            }));
        }

        List<ResponseEntity<Map<String, String>>> responses = new ArrayList<>();
        for (Future<ResponseEntity<Map<String, String>>> future : futures) {
            responses.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // All 5 invocations must successfully return 2xx (either 200 OK or 202 Accepted)
        for (ResponseEntity<Map<String, String>> response : responses) {
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }

        // 5. Verify that FundingOperation is settled as SUCCEEDED
        FundingOperation settledFunding = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(settledFunding.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(settledFunding.getJournalTransactionId()).isNotNull();

        // 6. Verify provider_events table recorded exactly 1 applied event
        Long appliedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_events WHERE event_id = ? AND processing_status = 'APPLIED'",
                Long.class, eventId
        );
        assertThat(appliedCount).isEqualTo(1L);

        // 7. Independent Oracle Invariant Audit
        try (Connection conn = dataSource.getConnection()) {
            List<InvariantCheckResult> checks = new ArrayList<>();

            // A. Structural journal validity
            checks.addAll(oracle.checkJournalStructure(conn));

            // B. Debit total == Credit total
            checks.addAll(oracle.checkDebitCreditEquality(conn));

            // C. Balance snapshots reconstruct accurately from immutable POSTED journals
            checks.addAll(oracle.checkSnapshotIntegrity(conn, customerAccount.getId()));

            // D. Available balance valid
            checks.add(oracle.checkAvailableBalance(conn, customerAccount.getId()));

            // E. Exactly 1 settlement journal transaction exists for this funding operation
            checks.add(oracle.checkSettlementJournalCount(conn, settledFunding.getJournalTransactionId(), 1));

            assertThat(checks).allMatch(InvariantCheckResult::passed);
        }
    }

    private String computeHmacSignature(String timestamp, byte[] rawBody, String secret) throws Exception {
        byte[] timestampBytes = timestamp.getBytes(StandardCharsets.UTF_8);
        byte[] dotBytes = ".".getBytes(StandardCharsets.UTF_8);
        byte[] canonicalBytes = new byte[timestampBytes.length + dotBytes.length + rawBody.length];

        System.arraycopy(timestampBytes, 0, canonicalBytes, 0, timestampBytes.length);
        System.arraycopy(dotBytes, 0, canonicalBytes, timestampBytes.length, dotBytes.length);
        System.arraycopy(rawBody, 0, canonicalBytes, timestampBytes.length + dotBytes.length, rawBody.length);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(canonicalBytes);

        return "sha256=" + HexFormat.of().formatHex(digest);
    }
}
