package com.ledgerguard.provider.application;

import com.ledgerguard.funding.application.FundingFailureService;
import com.ledgerguard.funding.application.FundingPollingHelper;
import com.ledgerguard.funding.application.FundingSettlementService;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.funding.infrastructure.PspOperationResponse;
import com.ledgerguard.funding.infrastructure.PspProtocolException;
import com.ledgerguard.funding.infrastructure.PspTransportException;
import com.ledgerguard.payout.application.PayoutFailureService;
import com.ledgerguard.payout.application.PayoutPollingHelper;
import com.ledgerguard.payout.application.PayoutSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Background status recovery poller in ledgerguard-api.
 * <p>
 * Periodically executes:
 * - Step 0: Exhaustion finalizer (short DB transaction)
 * - Step A: Claim due items (short DB transaction)
 * - Step B: Outbound provider GET outside DB transactions
 * - Step C: Apply terminal settlement or failure outcome (short DB transaction)
 */
@Service
public class ProviderStatusPollingService {

    private static final Logger log = LoggerFactory.getLogger(ProviderStatusPollingService.class);

    private final FundingPollingHelper fundingPollingHelper;
    private final PayoutPollingHelper payoutPollingHelper;
    private final FundingSettlementService fundingSettlementService;
    private final FundingFailureService fundingFailureService;
    private final PayoutSettlementService payoutSettlementService;
    private final PayoutFailureService payoutFailureService;
    private final PspClient pspClient;

    private final boolean pollingEnabled;
    private final int maxAttempts;
    private final int retryDelaySeconds;
    private final int batchSize;

    public ProviderStatusPollingService(
            FundingPollingHelper fundingPollingHelper,
            PayoutPollingHelper payoutPollingHelper,
            FundingSettlementService fundingSettlementService,
            FundingFailureService fundingFailureService,
            PayoutSettlementService payoutSettlementService,
            PayoutFailureService payoutFailureService,
            PspClient pspClient,
            @Value("${ledgerguard.psp.polling.enabled:true}") boolean pollingEnabled,
            @Value("${ledgerguard.psp.polling.max-attempts:10}") int maxAttempts,
            @Value("${ledgerguard.psp.polling.retry-delay-seconds:5}") int retryDelaySeconds,
            @Value("${ledgerguard.psp.polling.batch-size:10}") int batchSize
    ) {
        this.fundingPollingHelper = fundingPollingHelper;
        this.payoutPollingHelper = payoutPollingHelper;
        this.fundingSettlementService = fundingSettlementService;
        this.fundingFailureService = fundingFailureService;
        this.payoutSettlementService = payoutSettlementService;
        this.payoutFailureService = payoutFailureService;
        this.pspClient = pspClient;
        this.pollingEnabled = pollingEnabled;
        this.maxAttempts = maxAttempts;
        this.retryDelaySeconds = retryDelaySeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledgerguard.psp.polling.interval-ms:5000}")
    public void scheduledPoll() {
        if (!pollingEnabled) {
            return;
        }
        pollPendingOperations();
    }

    public void pollPendingOperations() {
        Instant now = Instant.now();

        // 1. Funding Polling Cycle
        try {
            // Step 0: Exhaustion finalizer
            fundingPollingHelper.finalizeExhausted(now, maxAttempts, batchSize);

            // Step A: Claim due rows
            List<FundingPollingHelper.ClaimedPollItem> claimedFunding =
                    fundingPollingHelper.claimDue(now, maxAttempts, retryDelaySeconds, batchSize);

            // Step B & C: Network GET and outcome application
            for (FundingPollingHelper.ClaimedPollItem item : claimedFunding) {
                pollFundingItem(item);
            }
        } catch (Exception ex) {
            log.error("Error during funding status recovery poll cycle: {}", ex.getMessage(), ex);
        }

        // 2. Payout Polling Cycle
        try {
            // Step 0: Exhaustion finalizer
            payoutPollingHelper.finalizeExhausted(now, maxAttempts, batchSize);

            // Step A: Claim due rows
            List<PayoutPollingHelper.ClaimedPollItem> claimedPayout =
                    payoutPollingHelper.claimDue(now, maxAttempts, retryDelaySeconds, batchSize);

            // Step B & C: Network GET and outcome application
            for (PayoutPollingHelper.ClaimedPollItem item : claimedPayout) {
                pollPayoutItem(item);
            }
        } catch (Exception ex) {
            log.error("Error during payout status recovery poll cycle: {}", ex.getMessage(), ex);
        }
    }

    private void pollFundingItem(FundingPollingHelper.ClaimedPollItem item) {
        try {
            Optional<PspOperationResponse> optResponse = pspClient.getOperationByClientOperationId(item.id());
            if (optResponse.isEmpty()) {
                // 404 from provider
                if (item.attemptCount() >= maxAttempts) {
                    fundingPollingHelper.markReconciliationRequiredIfAttemptsExhausted(item.id(), maxAttempts);
                }
                return;
            }

            PspOperationResponse response = optResponse.get();
            if ("SUCCEEDED".equalsIgnoreCase(response.status())) {
                fundingSettlementService.settleFunding(item.id(), response);
            } else if ("FAILED".equalsIgnoreCase(response.status())) {
                fundingFailureService.failFunding(item.id(), response.providerOperationId(), Instant.now());
            } else {
                // Provider returned PROCESSING
                if (item.attemptCount() >= maxAttempts) {
                    fundingPollingHelper.markReconciliationRequiredIfAttemptsExhausted(item.id(), maxAttempts);
                }
            }
        } catch (PspTransportException | PspProtocolException ex) {
            log.warn("Network/Protocol error polling funding {}: {}", item.id(), ex.getMessage());
            if (item.attemptCount() >= maxAttempts) {
                fundingPollingHelper.markReconciliationRequiredIfAttemptsExhausted(item.id(), maxAttempts);
            }
        } catch (Exception ex) {
            log.error("Unexpected error polling funding {}: {}", item.id(), ex.getMessage(), ex);
            if (item.attemptCount() >= maxAttempts) {
                fundingPollingHelper.markReconciliationRequiredIfAttemptsExhausted(item.id(), maxAttempts);
            }
        }
    }

    private void pollPayoutItem(PayoutPollingHelper.ClaimedPollItem item) {
        try {
            Optional<PspOperationResponse> optResponse = pspClient.getOperationByClientOperationId(item.id());
            if (optResponse.isEmpty()) {
                // 404 from provider
                if (item.attemptCount() >= maxAttempts) {
                    payoutPollingHelper.markReconciliationRequiredIfAttemptsExhausted(item.id(), maxAttempts);
                }
                return;
            }

            PspOperationResponse response = optResponse.get();
            if ("SUCCEEDED".equalsIgnoreCase(response.status())) {
                payoutSettlementService.settlePayout(item.id(), response);
            } else if ("FAILED".equalsIgnoreCase(response.status())) {
                payoutFailureService.failPayout(item.id(), response.providerOperationId(), Instant.now());
            } else {
                // Provider returned PROCESSING
                if (item.attemptCount() >= maxAttempts) {
                    payoutPollingHelper.markReconciliationRequiredIfAttemptsExhausted(item.id(), maxAttempts);
                }
            }
        } catch (PspTransportException | PspProtocolException ex) {
            log.warn("Network/Protocol error polling payout {}: {}", item.id(), ex.getMessage());
            if (item.attemptCount() >= maxAttempts) {
                payoutPollingHelper.markReconciliationRequiredIfAttemptsExhausted(item.id(), maxAttempts);
            }
        } catch (Exception ex) {
            log.error("Unexpected error polling payout {}: {}", item.id(), ex.getMessage(), ex);
            if (item.attemptCount() >= maxAttempts) {
                payoutPollingHelper.markReconciliationRequiredIfAttemptsExhausted(item.id(), maxAttempts);
            }
        }
    }
}
