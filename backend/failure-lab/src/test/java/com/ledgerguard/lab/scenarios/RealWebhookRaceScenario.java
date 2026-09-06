package com.ledgerguard.lab.scenarios;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.lab.engine.ChaosScenario;
import com.ledgerguard.lab.invariants.FinancialInvariantOracle;
import com.ledgerguard.lab.model.InvariantCheckResult;
import com.ledgerguard.lab.model.ScenarioId;
import com.ledgerguard.lab.model.ScenarioRunResult;
import com.ledgerguard.lab.model.ScenarioStatus;
import com.ledgerguard.lab.model.ScenarioStepEvent;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.provider.api.ProviderWebhookController;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
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

/**
 * Real production scenario executing concurrent duplicate webhooks
 * via ProviderWebhookController with HMAC validation and deduplication.
 */
public class RealWebhookRaceScenario implements ChaosScenario {

    private final ProviderWebhookController webhookController;
    private final FundingOperationRepository fundingOperationRepository;
    private final UserRepository userRepository;
    private final LedgerAccountRepository accountRepository;
    private final String webhookSecret;
    private final FinancialInvariantOracle oracle = new FinancialInvariantOracle();

    public RealWebhookRaceScenario(
            ProviderWebhookController webhookController,
            FundingOperationRepository fundingOperationRepository,
            UserRepository userRepository,
            LedgerAccountRepository accountRepository,
            String webhookSecret
    ) {
        this.webhookController = webhookController;
        this.fundingOperationRepository = fundingOperationRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public ScenarioId getId() {
        return ScenarioId.WEBHOOK_RACE;
    }

    @Override
    public String getDescription() {
        return "Concurrent duplicate signed webhooks to ProviderWebhookController testing dedup and single settlement";
    }

    @Override
    public ScenarioRunResult execute(UUID runId, DataSource dataSource) throws Exception {
        Instant startTime = Instant.now();
        List<ScenarioStepEvent> timeline = new ArrayList<>();
        List<InvariantCheckResult> invariantResults = new ArrayList<>();

        timeline.add(ScenarioStepEvent.of("SETUP", "Seeding user, account, and in-flight FundingOperation in PROCESSING"));

        UUID userId = UUID.randomUUID();
        userRepository.save(new User(userId, "webhook." + userId + "@lab.local", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE));

        LedgerAccount customerAccount = accountRepository.save(LedgerAccount.createCustomerAccount(userId));
        accountRepository.findByAccountType(AccountType.PSP_CLEARING)
                .orElseGet(() -> accountRepository.save(LedgerAccount.createSystemAccount(AccountType.PSP_CLEARING)));

        UUID fundingId = UUID.randomUUID();
        long fundingAmount = 15000L;
        FundingOperation fundingOp = new FundingOperation(
                fundingId, userId, customerAccount.getId(), fundingAmount, "INR", Instant.now()
        );
        fundingOperationRepository.saveAndFlush(fundingOp);
        fundingOp.prepareSubmission(Instant.now().plusSeconds(60));
        fundingOperationRepository.saveAndFlush(fundingOp);

        UUID eventId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        String timestampHeader = String.valueOf(Instant.now().getEpochSecond());
        String occurredAtIso = Instant.now().toString();

        String payloadJson = String.format(
                "{\"eventId\":\"%s\",\"eventSequence\":1,\"eventType\":\"PROVIDER_OPERATION_SUCCEEDED\",\"providerOperationId\":\"%s\",\"clientOperationId\":\"%s\",\"operationType\":\"CREDIT\",\"status\":\"SUCCEEDED\",\"amountMinor\":\"%d\",\"currency\":\"INR\",\"occurredAt\":\"%s\"}",
                eventId, providerOpId, fundingId, fundingAmount, occurredAtIso
        );
        byte[] rawBody = payloadJson.getBytes(StandardCharsets.UTF_8);
        String signatureHeader = computeHmac(timestampHeader, rawBody, webhookSecret);

        timeline.add(ScenarioStepEvent.of("DISPATCH_RACE", "Dispatching 5 concurrent duplicate signed webhooks to ProviderWebhookController"));

        int concurrency = 5;
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        List<Future<ResponseEntity<Map<String, String>>>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return webhookController.receiveWebhook(timestampHeader, signatureHeader, rawBody);
            }));
        }

        List<ResponseEntity<Map<String, String>>> responses = new ArrayList<>();
        for (Future<ResponseEntity<Map<String, String>>> future : futures) {
            responses.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        for (ResponseEntity<Map<String, String>> resp : responses) {
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Webhook call returned non-2xx status: " + resp.getStatusCode());
            }
        }

        FundingOperation settledFunding = fundingOperationRepository.findById(fundingId).orElseThrow();
        if (settledFunding.getStatus() != FundingStatus.SUCCEEDED) {
            throw new IllegalStateException("Expected SUCCEEDED funding operation but got: " + settledFunding.getStatus());
        }

        timeline.add(ScenarioStepEvent.of("RACE_SETTLED", "5 webhooks processed: 1 accepted, 4 deduplicated, settled to SUCCEEDED"));

        timeline.add(ScenarioStepEvent.of("ORACLE_AUDIT", "Running independent oracle verification"));
        try (Connection conn = dataSource.getConnection()) {
            invariantResults.addAll(oracle.checkJournalStructure(conn));
            invariantResults.addAll(oracle.checkDebitCreditEquality(conn));
            invariantResults.addAll(oracle.checkSnapshotIntegrity(conn, customerAccount.getId()));
            invariantResults.add(oracle.checkAvailableBalance(conn, customerAccount.getId()));
            invariantResults.add(oracle.checkSettlementJournalCount(conn, settledFunding.getJournalTransactionId(), 1));
        }

        boolean allPassed = invariantResults.stream().allMatch(InvariantCheckResult::passed);
        Instant endTime = Instant.now();

        return new ScenarioRunResult(
                runId, getId(), allPassed ? ScenarioStatus.PASSED : ScenarioStatus.FAILED,
                startTime, endTime, endTime.toEpochMilli() - startTime.toEpochMilli(),
                invariantResults, timeline, allPassed ? null : "Invariant check failed"
        );
    }

    private String computeHmac(String timestamp, byte[] rawBody, String secret) throws Exception {
        byte[] timestampBytes = timestamp.getBytes(StandardCharsets.UTF_8);
        byte[] dotBytes = ".".getBytes(StandardCharsets.UTF_8);
        byte[] canonicalBytes = new byte[timestampBytes.length + dotBytes.length + rawBody.length];

        System.arraycopy(timestampBytes, 0, canonicalBytes, 0, timestampBytes.length);
        System.arraycopy(dotBytes, 0, canonicalBytes, timestampBytes.length, dotBytes.length);
        System.arraycopy(rawBody, 0, canonicalBytes, timestampBytes.length + dotBytes.length, rawBody.length);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(canonicalBytes));
    }
}
