package com.ledgerguard.reconciliation.application;

import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.funding.infrastructure.PspProtocolException;
import com.ledgerguard.funding.infrastructure.PspTransportException;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.reconciliation.domain.ReconciliationClassification;
import com.ledgerguard.reconciliation.domain.ReconciliationItem;
import com.ledgerguard.reconciliation.domain.ReconciliationLevel;
import com.ledgerguard.reconciliation.domain.ReconciliationProblemType;
import com.ledgerguard.reconciliation.infrastructure.ReconciliationItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Level 3 reconciliation: Provider Settlement.
 * <p>
 * Three-phase pattern per operation:
 * <ul>
 *   <li><b>Phase A</b>: Collect candidate IDs in a short READ_ONLY DB transaction.</li>
 *   <li><b>Phase B</b>: PSP HTTP GET with ZERO LedgerGuard DB transaction active.</li>
 *   <li><b>Phase C</b>: Re-read local operation under {@code SELECT ... FOR SHARE} in a
 *       REQUIRES_NEW transaction; classify against current state; persist evidence.</li>
 * </ul>
 * <p>
 * DETECTION ONLY — no financial table is modified. No settlement or failure
 * service is invoked from this class under any circumstances.
 */
@Service
public class ProviderSettlementChecker {

    private static final Logger log = LoggerFactory.getLogger(ProviderSettlementChecker.class);

    // Statuses included in Level 3 scan scope
    private static final List<String> FUNDING_SCAN_STATUSES =
            List.of("SUCCEEDED", "FAILED", "RECONCILIATION_REQUIRED", "PROCESSING", "UNKNOWN");
    private static final List<String> PAYOUT_SCAN_STATUSES =
            List.of("SUCCEEDED", "FAILED", "RECONCILIATION_REQUIRED", "PROCESSING", "UNKNOWN");

    private static final String FUNDING_ENTITY_TYPE = "FUNDING_OPERATION";
    private static final String PAYOUT_ENTITY_TYPE  = "PAYOUT";
    private static final String FUNDING_OP_TYPE     = "CREDIT";
    private static final String PAYOUT_OP_TYPE      = "DEBIT";
    private static final String EXPECTED_CURRENCY   = "INR";

    private final FundingOperationRepository fundingRepo;
    private final PayoutRepository payoutRepo;
    private final PspClient pspClient;
    private final ReconciliationItemRepository itemRepository;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public ProviderSettlementChecker(FundingOperationRepository fundingRepo,
                                     PayoutRepository payoutRepo,
                                     PspClient pspClient,
                                     ReconciliationItemRepository itemRepository,
                                     org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.fundingRepo = fundingRepo;
        this.payoutRepo  = payoutRepo;
        this.pspClient   = pspClient;
        this.itemRepository = itemRepository;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Executes Level 3 scan. Returns total number of operations checked.
     */
    public long check(UUID runId) {
        log.info("Level 3 (Provider Settlement) scan started for run {}", runId);

        List<UUID> fundingIds = collectFundingIds();
        List<UUID> payoutIds  = collectPayoutIds();

        long checked = 0;
        for (UUID id : fundingIds) {
            reconcileFunding(runId, id);
            checked++;
        }
        for (UUID id : payoutIds) {
            reconcilePayout(runId, id);
            checked++;
        }

        log.info("Level 3 (Provider Settlement) scan completed for run {}: {} operations checked", runId, checked);
        return checked;
    }

    // ── Phase A ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UUID> collectFundingIds() {
        List<UUID> ids = new ArrayList<>();
        fundingRepo.findAll().forEach(f -> {
            if (FUNDING_SCAN_STATUSES.contains(f.getStatus().name())) {
                ids.add(f.getId());
            }
        });
        return ids;
    }

    @Transactional(readOnly = true)
    public List<UUID> collectPayoutIds() {
        List<UUID> ids = new ArrayList<>();
        payoutRepo.findAll().forEach(p -> {
            if (PAYOUT_SCAN_STATUSES.contains(p.getStatus().name())) {
                ids.add(p.getId());
            }
        });
        return ids;
    }

    // ── Funding reconciliation ────────────────────────────────────────────────

    private void reconcileFunding(UUID runId, UUID fundingId) {
        // Phase B: PSP GET outside DB transaction
        assertNoActiveTransaction();

        Optional<PspOperationResponse> providerResponse;
        try {
            providerResponse = pspClient.getOperationByClientOperationId(fundingId);
        } catch (PspTransportException e) {
            log.warn("Level 3 provider transport failure for funding {}: {}", fundingId, e.getMessage());
            transactionTemplate.execute(status -> {
                persistProviderUnavailable(runId, fundingId, FUNDING_ENTITY_TYPE,
                        "Provider transport failure for funding " + fundingId + ": " + e.getMessage(), null);
                return null;
            });
            return;
        } catch (PspProtocolException e) {
            log.warn("Level 3 provider protocol error for funding {}: {}", fundingId, e.getMessage());
            transactionTemplate.execute(status -> {
                persistProviderUnavailable(runId, fundingId, FUNDING_ENTITY_TYPE,
                        "Provider protocol error for funding " + fundingId + ": " + e.getMessage(), null);
                return null;
            });
            return;
        }

        // Phase C: re-read local state under FOR UPDATE in dedicated transaction, classify, persist
        transactionTemplate.execute(status -> {
            classifyFundingResult(runId, fundingId, providerResponse);
            return null;
        });
    }

    public void classifyFundingResult(UUID runId, UUID fundingId, Optional<PspOperationResponse> providerResponse) {
        FundingOperation funding = fundingRepo.findByIdForUpdate(fundingId)
                .orElse(null);
        if (funding == null) {
            log.warn("Level 3: funding operation {} not found during Phase C re-read — skipping", fundingId);
            return;
        }

        FundingStatus localStatus = funding.getStatus();
        UUID localProviderOpId = funding.getProviderOperationId();
        long localAmount = funding.getAmountMinor();

        classify(runId, fundingId, FUNDING_ENTITY_TYPE, FUNDING_OP_TYPE,
                localStatus.name(), localProviderOpId, localAmount,
                providerResponse);
    }

    // ── Payout reconciliation ─────────────────────────────────────────────────

    private void reconcilePayout(UUID runId, UUID payoutId) {
        assertNoActiveTransaction();

        Optional<PspOperationResponse> providerResponse;
        try {
            providerResponse = pspClient.getOperationByClientOperationId(payoutId);
        } catch (PspTransportException e) {
            log.warn("Level 3 provider transport failure for payout {}: {}", payoutId, e.getMessage());
            transactionTemplate.execute(status -> {
                persistProviderUnavailable(runId, payoutId, PAYOUT_ENTITY_TYPE,
                        "Provider transport failure for payout " + payoutId + ": " + e.getMessage(), null);
                return null;
            });
            return;
        } catch (PspProtocolException e) {
            log.warn("Level 3 provider protocol error for payout {}: {}", payoutId, e.getMessage());
            transactionTemplate.execute(status -> {
                persistProviderUnavailable(runId, payoutId, PAYOUT_ENTITY_TYPE,
                        "Provider protocol error for payout " + payoutId + ": " + e.getMessage(), null);
                return null;
            });
            return;
        }

        transactionTemplate.execute(status -> {
            classifyPayoutResult(runId, payoutId, providerResponse);
            return null;
        });
    }

    public void classifyPayoutResult(UUID runId, UUID payoutId, Optional<PspOperationResponse> providerResponse) {
        Payout payout = payoutRepo.findByIdForUpdate(payoutId)
                .orElse(null);
        if (payout == null) {
            log.warn("Level 3: payout {} not found during Phase C re-read — skipping", payoutId);
            return;
        }

        PayoutStatus localStatus = payout.getStatus();
        UUID localProviderOpId = payout.getProviderOperationId();
        long localAmount = payout.getAmountMinor();

        classify(runId, payoutId, PAYOUT_ENTITY_TYPE, PAYOUT_OP_TYPE,
                localStatus.name(), localProviderOpId, localAmount,
                providerResponse);
    }

    // ── Core classification logic ─────────────────────────────────────────────

    /**
     * Applies the provider matrix from the implementation plan using the Phase C
     * re-read local state. No financial mutation.
     */
    private void classify(UUID runId, UUID entityId, String entityType, String expectedOpType,
                          String localStatus, UUID localProviderOpId, long localAmountMinor,
                          Optional<PspOperationResponse> providerResponse) {

        // 404 — provider has no record
        if (providerResponse.isEmpty()) {
            handleNotFound(runId, entityId, entityType, localStatus, localProviderOpId);
            return;
        }

        PspOperationResponse resp = providerResponse.get();
        String providerStatus = resp.status();

        // Identity validation before status classification
        String identityFailReason = validateIdentity(resp, entityId, expectedOpType,
                localAmountMinor, localProviderOpId);
        if (identityFailReason != null) {
            persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY,
                    ReconciliationProblemType.PROVIDER_IDENTITY_MISMATCH,
                    localStatus, providerStatus,
                    BigDecimal.valueOf(localAmountMinor), null,
                    identityFailReason);
            return;
        }

        // Validate provider status is supported
        if (providerStatus == null || providerStatus.isBlank()
                || (!"SUCCEEDED".equals(providerStatus) && !"FAILED".equals(providerStatus) && !"PROCESSING".equals(providerStatus))) {
            persistItem(runId, entityId, entityType,
                    ReconciliationClassification.UNRESOLVED,
                    ReconciliationProblemType.PROVIDER_UNAVAILABLE,
                    localStatus, providerStatus,
                    BigDecimal.valueOf(localAmountMinor), null,
                    "Provider returned unsupported, blank, or null status: '" + providerStatus + "'");
            return;
        }

        // Status classification by local state
        switch (localStatus) {
            case "SUCCEEDED" -> classifySucceeded(runId, entityId, entityType, localStatus, providerStatus,
                    localAmountMinor);
            case "FAILED"    -> classifyFailed(runId, entityId, entityType, localStatus, providerStatus,
                    localProviderOpId, localAmountMinor);
            case "RECONCILIATION_REQUIRED" -> classifyRR(runId, entityId, entityType, localStatus, providerStatus,
                    localAmountMinor);
            case "PROCESSING" -> classifyProcessing(runId, entityId, entityType, localStatus, providerStatus,
                    localAmountMinor);
            case "UNKNOWN"   -> classifyUnknown(runId, entityId, entityType, localStatus, providerStatus,
                    localAmountMinor);
            default -> log.debug("Level 3: skipping entity {} with local status {}", entityId, localStatus);
        }
    }

    private void handleNotFound(UUID runId, UUID entityId, String entityType,
                                String localStatus, UUID localProviderOpId) {
        switch (localStatus) {
            case "FAILED" -> {
                if (localProviderOpId == null) {
                    // Pre-acceptance failure — provider may legitimately have no record. HEALTHY.
                    log.debug("Level 3: HEALTHY — FAILED (no bound provider ID) + NOT_FOUND for {}", entityId);
                } else {
                    // Post-acceptance — provider should have a record. DISCREPANCY.
                    persistItem(runId, entityId, entityType,
                            ReconciliationClassification.DISCREPANCY,
                            ReconciliationProblemType.PROVIDER_NOT_FOUND,
                            localStatus, null,
                            null, null,
                            "FAILED operation " + entityId + " has bound providerOperationId=" + localProviderOpId +
                            " but provider returned 404");
                }
            }
            case "SUCCEEDED" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY,
                    ReconciliationProblemType.PROVIDER_NOT_FOUND,
                    localStatus, null, null, null,
                    "SUCCEEDED operation " + entityId + " not found at provider (404)");
            case "RECONCILIATION_REQUIRED", "UNKNOWN", "PROCESSING" ->
                    persistItem(runId, entityId, entityType,
                            ReconciliationClassification.UNRESOLVED,
                            ReconciliationProblemType.PROVIDER_NOT_FOUND,
                            localStatus, null, null, null,
                            "Operation " + entityId + " (local=" + localStatus + ") not found at provider (404); ambiguous");
            default -> log.debug("Level 3: NOT_FOUND for {} with local status {} — no item", entityId, localStatus);
        }
    }

    private void classifySucceeded(UUID runId, UUID entityId, String entityType, String localStatus,
                                   String providerStatus, long localAmount) {
        switch (providerStatus) {
            case "SUCCEEDED" -> log.debug("Level 3: HEALTHY — SUCCEEDED + provider SUCCEEDED for {}", entityId);
            case "FAILED"    -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "SUCCEEDED operation " + entityId + " but provider says FAILED");
            case "PROCESSING" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "SUCCEEDED operation " + entityId + " but provider still PROCESSING");
            default -> log.warn("Level 3: unknown provider status '{}' for {}", providerStatus, entityId);
        }
    }

    private void classifyFailed(UUID runId, UUID entityId, String entityType, String localStatus,
                                String providerStatus, UUID localProviderOpId, long localAmount) {
        if (localProviderOpId == null) {
            // Pre-acceptance failure
            switch (providerStatus) {
                case "FAILED" -> persistItem(runId, entityId, entityType,
                        ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                        localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                        "Pre-acceptance FAILED operation " + entityId + ": provider has a FAILED record despite no bound provider ID");
                case "PROCESSING" -> persistItem(runId, entityId, entityType,
                        ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                        localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                        "Pre-acceptance FAILED operation " + entityId + ": provider has an active PROCESSING record");
                case "SUCCEEDED" -> persistItem(runId, entityId, entityType,
                        ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                        localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                        "SERIOUS DISCREPANCY: pre-acceptance FAILED operation " + entityId +
                        " but provider says SUCCEEDED — money may have moved");
                default -> log.warn("Level 3: unknown provider status '{}' for {}", providerStatus, entityId);
            }
        } else {
            // Post-acceptance failure — provider operation bound
            switch (providerStatus) {
                case "FAILED" -> log.debug("Level 3: HEALTHY — FAILED (bound provider ID) + provider FAILED for {}", entityId);
                case "PROCESSING" -> persistItem(runId, entityId, entityType,
                        ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                        localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                        "FAILED operation " + entityId + " (providerOpId=" + localProviderOpId + ") but provider still PROCESSING");
                case "SUCCEEDED" -> persistItem(runId, entityId, entityType,
                        ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                        localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                        "SERIOUS DISCREPANCY: FAILED operation " + entityId +
                        " (providerOpId=" + localProviderOpId + ") but provider says SUCCEEDED — money may have moved");
                default -> log.warn("Level 3: unknown provider status '{}' for {}", providerStatus, entityId);
            }
        }
    }

    private void classifyRR(UUID runId, UUID entityId, String entityType, String localStatus,
                            String providerStatus, long localAmount) {
        switch (providerStatus) {
            case "SUCCEEDED" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "RECONCILIATION_REQUIRED operation " + entityId + ": provider says SUCCEEDED; local awaits resolution");
            case "FAILED" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "RECONCILIATION_REQUIRED operation " + entityId + ": provider says FAILED; local awaits resolution");
            case "PROCESSING" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.UNRESOLVED, ReconciliationProblemType.PROVIDER_STILL_PROCESSING,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "RECONCILIATION_REQUIRED operation " + entityId + ": provider still PROCESSING");
            default -> log.warn("Level 3: unknown provider status '{}' for {}", providerStatus, entityId);
        }
    }

    private void classifyProcessing(UUID runId, UUID entityId, String entityType, String localStatus,
                                    String providerStatus, long localAmount) {
        switch (providerStatus) {
            case "SUCCEEDED" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "UN-CLEARED: PROCESSING operation " + entityId + " provider says SUCCEEDED — funds may have settled without local journal");
            case "FAILED" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "PROCESSING operation " + entityId + " provider says FAILED — local status stale");
            case "PROCESSING" -> log.debug("Level 3: HEALTHY (in-flight) — PROCESSING + provider PROCESSING for {}", entityId);
            default -> log.warn("Level 3: unknown provider status '{}' for {}", providerStatus, entityId);
        }
    }

    private void classifyUnknown(UUID runId, UUID entityId, String entityType, String localStatus,
                                 String providerStatus, long localAmount) {
        switch (providerStatus) {
            case "SUCCEEDED" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "UNKNOWN operation " + entityId + " provider says SUCCEEDED — Phase 23 poller may have missed settlement");
            case "FAILED" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.DISCREPANCY, ReconciliationProblemType.PROVIDER_STATUS_MISMATCH,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "UNKNOWN operation " + entityId + " provider says FAILED");
            case "PROCESSING" -> persistItem(runId, entityId, entityType,
                    ReconciliationClassification.UNRESOLVED, ReconciliationProblemType.PROVIDER_STILL_PROCESSING,
                    localStatus, providerStatus, BigDecimal.valueOf(localAmount), null,
                    "UNKNOWN operation " + entityId + " provider still PROCESSING");
            default -> log.warn("Level 3: unknown provider status '{}' for {}", providerStatus, entityId);
        }
    }

    // ── Identity validation ───────────────────────────────────────────────────

    /**
     * Returns null if identity is valid; otherwise returns the human-readable failure reason.
     */
    private String validateIdentity(PspOperationResponse resp, UUID expectedClientId,
                                    String expectedOpType, long expectedAmountMinor,
                                    UUID localProviderOpId) {
        if (resp.providerOperationId() == null) {
            return "Provider response has null providerOperationId for client operation " + expectedClientId;
        }
        if (!expectedClientId.equals(resp.clientOperationId())) {
            return "clientOperationId mismatch: expected=" + expectedClientId +
                   " got=" + resp.clientOperationId();
        }
        if (!expectedOpType.equals(resp.operationType())) {
            return "operationType mismatch: expected=" + expectedOpType +
                   " got=" + resp.operationType();
        }
        long parsedAmount;
        try {
            parsedAmount = Long.parseLong(resp.amountMinor());
        } catch (NumberFormatException e) {
            return "Provider amountMinor is not a valid long: '" + resp.amountMinor() + "'";
        }
        if (parsedAmount != expectedAmountMinor) {
            return "amountMinor mismatch: expected=" + expectedAmountMinor +
                   " got=" + parsedAmount;
        }
        if (!EXPECTED_CURRENCY.equals(resp.currency())) {
            return "currency mismatch: expected=" + EXPECTED_CURRENCY +
                   " got=" + resp.currency();
        }
        if (localProviderOpId != null && !localProviderOpId.equals(resp.providerOperationId())) {
            return "providerOperationId mismatch: local bound=" + localProviderOpId +
                   " provider returned=" + resp.providerOperationId();
        }
        return null; // identity valid
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    private void persistItem(UUID runId, UUID entityId, String entityType,
                             ReconciliationClassification classification,
                             ReconciliationProblemType problemType,
                             String observedLocalStatus, String providerStatus,
                             BigDecimal expectedValue, BigDecimal actualValue,
                             String description) {
        ReconciliationItem item = ReconciliationItem.builder()
                .runId(runId)
                .classification(classification)
                .level(ReconciliationLevel.PROVIDER_SETTLEMENT)
                .problemType(problemType)
                .entityType(entityType)
                .entityId(entityId)
                .observedLocalStatus(observedLocalStatus)
                .providerStatus(providerStatus)
                .expectedValue(expectedValue)
                .actualValue(actualValue)
                .description(description)
                .build();
        itemRepository.saveAndFlush(item);
        log.warn("Level 3 {} recorded: run={} entity={} type={} localStatus={} providerStatus={}",
                classification, runId, entityId, problemType, observedLocalStatus, providerStatus);
    }

    public void persistProviderUnavailable(UUID runId, UUID entityId, String entityType,
                                           String description, String observedLocalStatus) {
        ReconciliationItem item = ReconciliationItem.builder()
                .runId(runId)
                .classification(ReconciliationClassification.UNRESOLVED)
                .level(ReconciliationLevel.PROVIDER_SETTLEMENT)
                .problemType(ReconciliationProblemType.PROVIDER_UNAVAILABLE)
                .entityType(entityType)
                .entityId(entityId)
                .observedLocalStatus(observedLocalStatus)
                .description(description)
                .build();
        itemRepository.saveAndFlush(item);
    }

    private void assertNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "ProviderSettlementChecker: PSP GET must not execute within an active DB transaction");
        }
    }
}
