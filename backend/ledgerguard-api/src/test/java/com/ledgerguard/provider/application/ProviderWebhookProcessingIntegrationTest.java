package com.ledgerguard.provider.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.domain.HoldStatus;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.provider.domain.ProviderEvent;
import com.ledgerguard.provider.domain.ProviderProcessingStatus;
import com.ledgerguard.provider.infrastructure.ProviderEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class ProviderWebhookProcessingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ProviderEventRepository providerEventRepository;

    @Autowired
    private FundingOperationRepository fundingOperationRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private BalanceHoldRepository balanceHoldRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private UUID customerUserId;
    private LedgerAccount customerAccount;
    private LedgerAccount pspClearingAccount;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        customerUserId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) VALUES (?, ?, 'hash', 'CUSTOMER', 'ACTIVE', ?, ?)",
                customerUserId, "webhook-user-" + customerUserId + "@example.com", ts, ts
        );

        customerAccount = new LedgerAccount(UUID.randomUUID(), customerUserId, AccountType.CUSTOMER, "INR", AccountStatus.ACTIVE, now, now);
        ledgerAccountRepository.saveAndFlush(customerAccount);

        jdbcTemplate.update("UPDATE ledger_accounts SET status = 'CLOSED' WHERE account_type = 'PSP_CLEARING' AND currency = 'INR'");
        pspClearingAccount = new LedgerAccount(UUID.randomUUID(), null, AccountType.PSP_CLEARING, "INR", AccountStatus.ACTIVE, now, now);
        ledgerAccountRepository.saveAndFlush(pspClearingAccount);
    }

    private FundingOperation createSubmittedFunding(UUID fundingId, UUID customerUserId, UUID customerAccountId, long amountMinor) {
        FundingOperation funding = new FundingOperation(
                fundingId, customerUserId, customerAccountId, amountMinor, "INR", Instant.now()
        );
        fundingOperationRepository.saveAndFlush(funding);
        funding.prepareSubmission(Instant.now().plusSeconds(10));
        return fundingOperationRepository.saveAndFlush(funding);
    }

    private Payout createSubmittedPayout(UUID payoutId, UUID customerUserId, UUID customerAccountId, UUID balanceHoldId, long amountMinor) {
        Payout payout = new Payout(
                payoutId, customerUserId, customerAccountId, balanceHoldId, amountMinor, "INR", Instant.now()
        );
        payoutRepository.saveAndFlush(payout);
        payout.prepareSubmission(Instant.now().plusSeconds(10));
        return payoutRepository.saveAndFlush(payout);
    }

    private String sign(long timestamp, byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(String.valueOf(timestamp).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            byte[] signatureBytes = mac.doFinal(payload);
            return "sha256=" + HexFormat.of().formatHex(signatureBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResultActions sendWebhook(String jsonPayload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        byte[] body = jsonPayload.getBytes(StandardCharsets.UTF_8);
        String signature = sign(timestamp, body, RUNTIME_WEBHOOK_SECRET);

        return mockMvc.perform(post("/api/provider/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-PSP-Webhook-Timestamp", String.valueOf(timestamp))
                .header("X-PSP-Webhook-Signature", signature)
                .content(body));
    }

    private String buildWebhookJson(
            UUID eventId,
            long eventSequence,
            String eventType,
            UUID providerOpId,
            UUID clientOpId,
            String opType,
            String status,
            long amountMinor
    ) {
        return """
                {
                    "eventId": "%s",
                    "eventSequence": %d,
                    "eventType": "%s",
                    "providerOperationId": "%s",
                    "clientOperationId": "%s",
                    "operationType": "%s",
                    "status": "%s",
                    "amountMinor": "%d",
                    "currency": "INR",
                    "occurredAt": "%s"
                }
                """.formatted(eventId, eventSequence, eventType, providerOpId, clientOpId, opType, status, amountMinor, Instant.now().toString());
    }

    @Test
    @DisplayName("CREDIT SUCCEEDED settles funding, marks event APPLIED, and moves money once")
    void fundingSucceededSettlesAndAppliesOnce() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 5000L);

        UUID eventId = UUID.randomUUID();
        String json = buildWebhookJson(
                eventId, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 5000L
        );

        sendWebhook(json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        // Verify provider event applied
        ProviderEvent event = providerEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);
        assertThat(event.getProcessedAt()).isNotNull();

        // Verify funding settled
        FundingOperation settledFunding = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(settledFunding.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        assertThat(settledFunding.getJournalTransactionId()).isNotNull();

        // Verify journal entry exists
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Identical redelivered webhook returns 200 OK without creating duplicate rows or journals")
    void fundingSucceededIdenticalReplay() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 5000L);

        UUID eventId = UUID.randomUUID();
        String json = buildWebhookJson(
                eventId, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 5000L
        );

        // First delivery
        sendWebhook(json).andExpect(status().isOk());

        // Second delivery (identical body, same eventId)
        sendWebhook(json).andExpect(status().isOk());

        // Verify provider_events has exactly 1 row
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_events WHERE provider_operation_id = ?",
                Integer.class, providerOpId
        );
        assertThat(eventCount).isEqualTo(1);

        // Verify exactly 1 journal entry exists
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Same-terminal event progression (SUCCEEDED -> SUCCEEDED) marks event APPLIED with zero new journals")
    void sameTerminalSucceededProgressionIsAppliedNoOp() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 4000L);

        // Sequence 1: SUCCEEDED
        UUID eventId1 = UUID.randomUUID();
        String json1 = buildWebhookJson(eventId1, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 4000L);
        sendWebhook(json1).andExpect(status().isOk());

        // Sequence 2: SUCCEEDED (same terminal status, fresh eventId)
        UUID eventId2 = UUID.randomUUID();
        String json2 = buildWebhookJson(eventId2, 2, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 4000L);
        sendWebhook(json2).andExpect(status().isOk());

        ProviderEvent event2 = providerEventRepository.findById(eventId2).orElseThrow();
        assertThat(event2.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);

        // Still only 1 journal entry exists
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("DEBIT SUCCEEDED settles payout, consumes hold, marks event APPLIED")
    void payoutSucceededSettlesAndConsumesHold() throws Exception {
        // Initial customer balance via credit
        UUID fundingId = UUID.randomUUID();
        UUID fundingProviderOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 10000L);
        sendWebhook(buildWebhookJson(UUID.randomUUID(), 1, "PROVIDER_OPERATION_SUCCEEDED", fundingProviderOpId, fundingId, "CREDIT", "SUCCEEDED", 10000L))
                .andExpect(status().isOk());

        // Create balance hold for payout
        BalanceHold hold = BalanceHold.create(
                UUID.randomUUID(), customerAccount.getId(), 3000L, "INR",
                Instant.now().plus(Duration.ofMinutes(15)), Instant.now()
        );
        balanceHoldRepository.saveAndFlush(hold);

        UUID payoutId = UUID.randomUUID();
        UUID payoutProviderOpId = UUID.randomUUID();
        createSubmittedPayout(payoutId, customerUserId, customerAccount.getId(), hold.getId(), 3000L);

        UUID eventId = UUID.randomUUID();
        String json = buildWebhookJson(
                eventId, 1, "PROVIDER_OPERATION_SUCCEEDED", payoutProviderOpId, payoutId, "DEBIT", "SUCCEEDED", 3000L
        );

        sendWebhook(json).andExpect(status().isOk());

        // Verify payout SUCCEEDED
        Payout settledPayout = payoutRepository.findById(payoutId).orElseThrow();
        assertThat(settledPayout.getStatus()).isEqualTo(PayoutStatus.SUCCEEDED);

        // Verify hold CONSUMED
        BalanceHold consumedHold = balanceHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(consumedHold.getStatus()).isEqualTo(HoldStatus.CONSUMED);

        // Verify provider event APPLIED
        ProviderEvent event = providerEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);
    }

    @Test
    @DisplayName("DEBIT FAILED releases hold, marks payout FAILED atomically via PayoutFailureService")
    void payoutFailedReleasesHoldAndFailsPayout() throws Exception {
        // Initial customer balance via credit
        UUID fundingId = UUID.randomUUID();
        UUID fundingProviderOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 10000L);
        sendWebhook(buildWebhookJson(UUID.randomUUID(), 1, "PROVIDER_OPERATION_SUCCEEDED", fundingProviderOpId, fundingId, "CREDIT", "SUCCEEDED", 10000L))
                .andExpect(status().isOk());

        BalanceHold hold = BalanceHold.create(
                UUID.randomUUID(), customerAccount.getId(), 2500L, "INR",
                Instant.now().plus(Duration.ofMinutes(15)), Instant.now()
        );
        balanceHoldRepository.saveAndFlush(hold);

        UUID payoutId = UUID.randomUUID();
        UUID payoutProviderOpId = UUID.randomUUID();
        createSubmittedPayout(payoutId, customerUserId, customerAccount.getId(), hold.getId(), 2500L);

        UUID eventId = UUID.randomUUID();
        String json = buildWebhookJson(
                eventId, 1, "PROVIDER_OPERATION_FAILED", payoutProviderOpId, payoutId, "DEBIT", "FAILED", 2500L
        );

        sendWebhook(json).andExpect(status().isOk());

        // Verify payout FAILED
        Payout failedPayout = payoutRepository.findById(payoutId).orElseThrow();
        assertThat(failedPayout.getStatus()).isEqualTo(PayoutStatus.FAILED);

        // Verify hold RELEASED
        BalanceHold releasedHold = balanceHoldRepository.findById(hold.getId()).orElseThrow();
        assertThat(releasedHold.getStatus()).isEqualTo(HoldStatus.RELEASED);

        // Verify provider event APPLIED
        ProviderEvent event = providerEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);

        // Verify NO payout settlement journal entry was posted
        assertThat(failedPayout.getJournalTransactionId()).isNull();
    }

    @Test
    @DisplayName("CREDIT FAILED marks event APPLIED and transitions FundingOperation to FAILED")
    void fundingFailedMarksFundingFailed() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 2000L);

        UUID eventId = UUID.randomUUID();
        String json = buildWebhookJson(
                eventId, 1, "PROVIDER_OPERATION_FAILED", providerOpId, fundingId, "CREDIT", "FAILED", 2000L
        );

        sendWebhook(json).andExpect(status().isOk());

        // Verify provider event APPLIED
        ProviderEvent event = providerEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);

        // FundingOperation transitions to FAILED
        FundingOperation failed = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(FundingStatus.FAILED);
        assertThat(failed.getProviderOperationId()).isEqualTo(providerOpId);
        assertThat(failed.getJournalTransactionId()).isNull();
    }

    @Test
    @DisplayName("Out of order delivery: sequence 2 returns 202 ACCEPTED and remains PENDING; sequence 1 unblocks it in order")
    void outOfOrderSequenceQueueing() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 3500L);

        // Send sequence 2 first
        UUID eventId2 = UUID.randomUUID();
        String json2 = buildWebhookJson(
                eventId2, 2, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 3500L
        );
        sendWebhook(json2).andExpect(status().isAccepted());

        // Verify sequence 2 is PENDING
        ProviderEvent event2 = providerEventRepository.findById(eventId2).orElseThrow();
        assertThat(event2.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.PENDING);

        // 0 journals posted so far
        Integer journalsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalsBefore).isEqualTo(0);

        // Send sequence 1 (PROCESSING)
        UUID eventId1 = UUID.randomUUID();
        String json1 = buildWebhookJson(
                eventId1, 1, "PROVIDER_OPERATION_PROCESSING", providerOpId, fundingId, "CREDIT", "PROCESSING", 3500L
        );
        sendWebhook(json1).andExpect(status().isOk());

        // Verify sequence 1 is APPLIED
        ProviderEvent event1 = providerEventRepository.findById(eventId1).orElseThrow();
        assertThat(event1.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);

        // Sequence 2 is now unblocked and also APPLIED!
        ProviderEvent event2After = providerEventRepository.findById(eventId2).orElseThrow();
        assertThat(event2After.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);

        // Funding settled with exactly 1 journal
        FundingOperation settled = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
        Integer journalsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalsAfter).isEqualTo(1);
    }

    @Test
    @DisplayName("Status regression (SUCCEEDED -> PROCESSING) marks event IGNORED with zero money movement")
    void statusRegressionMarkedIgnored() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 1500L);

        // Sequence 1: SUCCEEDED
        UUID eventId1 = UUID.randomUUID();
        sendWebhook(buildWebhookJson(eventId1, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 1500L))
                .andExpect(status().isOk());

        // Sequence 2: PROCESSING (illegal regression)
        UUID eventId2 = UUID.randomUUID();
        sendWebhook(buildWebhookJson(eventId2, 2, "PROVIDER_OPERATION_PROCESSING", providerOpId, fundingId, "CREDIT", "PROCESSING", 1500L))
                .andExpect(status().isOk());

        ProviderEvent event2 = providerEventRepository.findById(eventId2).orElseThrow();
        assertThat(event2.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.IGNORED);
        assertThat(event2.getProcessedAt()).isNotNull();

        // Still only 1 journal entry exists
        Integer journals = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journals).isEqualTo(1);
    }

    @Test
    @DisplayName("Sequence ownership conflict (different eventId for same providerOpId and sequence) returns 409 Conflict")
    void sequenceOwnershipConflictReturns409() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 2200L);

        // Sequence 1 owned by eventId1
        UUID eventId1 = UUID.randomUUID();
        sendWebhook(buildWebhookJson(eventId1, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 2200L))
                .andExpect(status().isOk());

        // Sequence 1 attempted by eventId2
        UUID eventId2 = UUID.randomUUID();
        sendWebhook(buildWebhookJson(eventId2, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 2200L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_EVENT_CONFLICT"));
    }

    @Test
    @DisplayName("Changed payload for existing eventId returns 409 Conflict")
    void changedPayloadForSameEventIdReturns409() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 5000L);

        UUID eventId = UUID.randomUUID();
        String json1 = buildWebhookJson(eventId, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 5000L);
        sendWebhook(json1).andExpect(status().isOk());

        // Changed amount to 6000 with the same eventId
        String json2 = buildWebhookJson(eventId, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 6000L);
        sendWebhook(json2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_EVENT_CONFLICT"));
    }

    @Test
    @DisplayName("20 concurrent identical webhook deliveries result in 1 provider_events row, 1 journal, and 0 errors")
    void concurrentIdenticalWebhooksYieldSingleSettlement() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 7500L);

        UUID eventId = UUID.randomUUID();
        String json = buildWebhookJson(eventId, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 7500L);

        int concurrency = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        AtomicInteger okCount = new AtomicInteger(0);
        AtomicInteger otherCount = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int status = sendWebhook(json).andReturn().getResponse().getStatus();
                    if (status == 200) {
                        okCount.incrementAndGet();
                    } else {
                        otherCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        assertThat(okCount.get()).isEqualTo(concurrency);
        assertThat(otherCount.get()).isEqualTo(0);

        // Exactly 1 provider_events row
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_events WHERE provider_operation_id = ?",
                Integer.class, providerOpId
        );
        assertThat(eventCount).isEqualTo(1);

        // Exactly 1 journal
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent sequence ownership race: exactly 1 sequence owner, second request receives 409, 0 duplicate settlements")
    void concurrentSequenceOwnershipRace() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 8800L);

        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();
        String json1 = buildWebhookJson(eventId1, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 8800L);
        String json2 = buildWebhookJson(eventId2, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 8800L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            try {
                startLatch.await();
                statuses.add(sendWebhook(json1).andReturn().getResponse().getStatus());
            } catch (Exception e) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startLatch.await();
                statuses.add(sendWebhook(json2).andReturn().getResponse().getStatus());
            } catch (Exception e) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        assertThat(statuses).hasSize(2);
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // Exactly 1 provider_events row
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_events WHERE provider_operation_id = ?",
                Integer.class, providerOpId
        );
        assertThat(eventCount).isEqualTo(1);

        // Exactly 1 journal
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate redelivery of PENDING event retries ordered processing and transitions PENDING -> APPLIED")
    void pendingDuplicateRedeliveryRetriesProcessing() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 6000L);

        UUID eventId = UUID.randomUUID();
        String json = buildWebhookJson(
                eventId, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpId, fundingId, "CREDIT", "SUCCEEDED", 6000L
        );

        // Directly insert PENDING row simulating crash after Phase B before Phase C
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO provider_events (event_id, provider_operation_id, client_operation_id, event_sequence, " +
                        "event_type, operation_type, provider_status, amount_minor, currency, occurred_at, payload, " +
                        "processing_status, received_at, processed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'PENDING', ?, NULL)",
                eventId, providerOpId, fundingId, 1L,
                "PROVIDER_OPERATION_SUCCEEDED", "CREDIT", "SUCCEEDED",
                6000L, "INR", now, json, now
        );

        // Verify initial state: exactly 1 row in PENDING status, 0 journals
        ProviderEvent initial = providerEventRepository.findById(eventId).orElseThrow();
        assertThat(initial.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.PENDING);
        assertThat(initial.getProcessedAt()).isNull();

        Integer journalsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalsBefore).isEqualTo(0);

        // Redeliver exact same signed event
        sendWebhook(json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        // Assert: still exactly 1 row, but now APPLIED
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_events WHERE provider_operation_id = ?",
                Integer.class, providerOpId
        );
        assertThat(eventCount).isEqualTo(1);

        ProviderEvent after = providerEventRepository.findById(eventId).orElseThrow();
        assertThat(after.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);
        assertThat(after.getProcessedAt()).isNotNull();

        // Exactly 1 journal entry exists
        Integer journalsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalsAfter).isEqualTo(1);

        // Funding operation is SUCCEEDED
        FundingOperation settled = fundingOperationRepository.findById(fundingId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(FundingStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("Conflicting providerOperationId for same clientOperationId returns 409 Conflict without duplicate settlement")
    void conflictingProviderOperationForSameClientOperationId() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpIdA = UUID.randomUUID();
        UUID providerOpIdB = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 5000L);

        UUID eventIdA = UUID.randomUUID();
        String jsonA = buildWebhookJson(eventIdA, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpIdA, fundingId, "CREDIT", "SUCCEEDED", 5000L);
        UUID eventIdB = UUID.randomUUID();
        String jsonB = buildWebhookJson(eventIdB, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpIdB, fundingId, "CREDIT", "SUCCEEDED", 5000L);

        // Delivery A succeeds
        sendWebhook(jsonA).andExpect(status().isOk());

        // Delivery B attempts different providerOperationId for same clientOperationId -> 409 Conflict
        sendWebhook(jsonB)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_EVENT_CONFLICT"));

        // Exactly 1 journal
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalCount).isEqualTo(1);

        // Provider event A is APPLIED, provider event B is NOT APPLIED (remains PENDING because Phase C rolled back)
        ProviderEvent eventA = providerEventRepository.findById(eventIdA).orElseThrow();
        assertThat(eventA.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.APPLIED);

        ProviderEvent eventB = providerEventRepository.findById(eventIdB).orElseThrow();
        assertThat(eventB.getProcessingStatus()).isEqualTo(ProviderProcessingStatus.PENDING);
    }

    @Test
    @DisplayName("Concurrent conflicting providerOperationIds for same clientOperationId: exactly 1 settles, 1 receives 409")
    void concurrentConflictingProviderOperations() throws Exception {
        UUID fundingId = UUID.randomUUID();
        UUID providerOpIdA = UUID.randomUUID();
        UUID providerOpIdB = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 4200L);

        UUID eventIdA = UUID.randomUUID();
        UUID eventIdB = UUID.randomUUID();
        String jsonA = buildWebhookJson(eventIdA, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpIdA, fundingId, "CREDIT", "SUCCEEDED", 4200L);
        String jsonB = buildWebhookJson(eventIdB, 1, "PROVIDER_OPERATION_SUCCEEDED", providerOpIdB, fundingId, "CREDIT", "SUCCEEDED", 4200L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        executor.submit(() -> {
            try {
                startLatch.await();
                statuses.add(sendWebhook(jsonA).andReturn().getResponse().getStatus());
            } catch (Exception e) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startLatch.await();
                statuses.add(sendWebhook(jsonB).andReturn().getResponse().getStatus());
            } catch (Exception e) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // Exactly 1 journal
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN funding_operations fo ON fo.journal_transaction_id = jt.id WHERE fo.id = ?",
                Integer.class, fundingId
        );
        assertThat(journalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Different providerOperationId on settled payout returns 409 Conflict")
    void differentProviderIdOnSettledPayoutReturnsConflict() throws Exception {
        // Initial customer balance via credit
        UUID fundingId = UUID.randomUUID();
        UUID fundingProviderOpId = UUID.randomUUID();
        createSubmittedFunding(fundingId, customerUserId, customerAccount.getId(), 10000L);
        sendWebhook(buildWebhookJson(UUID.randomUUID(), 1, "PROVIDER_OPERATION_SUCCEEDED", fundingProviderOpId, fundingId, "CREDIT", "SUCCEEDED", 10000L))
                .andExpect(status().isOk());

        BalanceHold hold = BalanceHold.create(
                UUID.randomUUID(), customerAccount.getId(), 3000L, "INR",
                Instant.now().plus(Duration.ofMinutes(15)), Instant.now()
        );
        balanceHoldRepository.saveAndFlush(hold);

        UUID payoutId = UUID.randomUUID();
        UUID payoutProviderOpIdA = UUID.randomUUID();
        createSubmittedPayout(payoutId, customerUserId, customerAccount.getId(), hold.getId(), 3000L);

        // Event A settles payout
        UUID eventIdA = UUID.randomUUID();
        sendWebhook(buildWebhookJson(eventIdA, 1, "PROVIDER_OPERATION_SUCCEEDED", payoutProviderOpIdA, payoutId, "DEBIT", "SUCCEEDED", 3000L))
                .andExpect(status().isOk());

        // Event B with different providerOperationId -> 409 Conflict
        UUID payoutProviderOpIdB = UUID.randomUUID();
        UUID eventIdB = UUID.randomUUID();
        sendWebhook(buildWebhookJson(eventIdB, 1, "PROVIDER_OPERATION_SUCCEEDED", payoutProviderOpIdB, payoutId, "DEBIT", "SUCCEEDED", 3000L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_EVENT_CONFLICT"));

        // Only 1 journal entry exists
        Integer journalCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_transactions jt JOIN payouts p ON p.journal_transaction_id = jt.id WHERE p.id = ?",
                Integer.class, payoutId
        );
        assertThat(journalCount).isEqualTo(1);
    }
}
