package com.ledgerguard.psp.application;

import tools.jackson.databind.ObjectMapper;
import com.ledgerguard.psp.domain.*;
import com.ledgerguard.psp.infrastructure.ProviderOperationRepository;
import com.ledgerguard.psp.infrastructure.ProviderWebhookRepository;
import com.ledgerguard.psp.infrastructure.ScenarioRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class ProviderOperationService {

    private static final Logger log = LoggerFactory.getLogger(ProviderOperationService.class);

    private final ProviderOperationRepository operationRepository;
    private final ProviderWebhookRepository webhookRepository;
    private final ScenarioRegistry scenarioRegistry;
    private final ObjectMapper objectMapper;

    public ProviderOperationService(
            ProviderOperationRepository operationRepository,
            ProviderWebhookRepository webhookRepository,
            ScenarioRegistry scenarioRegistry,
            ObjectMapper objectMapper
    ) {
        this.operationRepository = operationRepository;
        this.webhookRepository = webhookRepository;
        this.scenarioRegistry = scenarioRegistry;
        this.objectMapper = objectMapper;
    }

    public record OperationExecutionResult(
            ProviderOperation operation,
            boolean isReplay,
            SimulatorScenario scenario,
            long delayMs
    ) {}

    @Transactional
    public OperationExecutionResult executeOperation(
            UUID clientOperationId,
            OperationType operationType,
            long amountMinor,
            String currency,
            String webhookUrl
    ) {
        ScenarioRegistry.ScenarioState scenarioState = scenarioRegistry.getState(clientOperationId);
        ScenarioConfig scenarioConfig = scenarioState.getConfig();

        // 1. Check for TEMPORARY_500 fault injection
        if (scenarioConfig.scenario() == SimulatorScenario.TEMPORARY_500) {
            if (scenarioState.consumeTemporaryFailure()) {
                log.warn("Injected TEMPORARY_500 failure for clientOperationId: {}", clientOperationId);
                throw new TemporaryFailureException("Simulated temporary 500 error before provider acceptance");
            }
        }

        UUID operationId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        SimulatorScenario effectiveScenario = scenarioConfig.scenario();

        // 2. Atomic insert
        int inserted = operationRepository.tryInsertOperation(
                operationId,
                clientOperationId,
                operationType.name(),
                amountMinor,
                currency,
                OperationStatus.SUCCEEDED.name(),
                effectiveScenario.name(),
                nowTs,
                nowTs
        );

        if (inserted == 1) {
            // Fresh operation created
            ProviderOperation operation = new ProviderOperation(
                    operationId,
                    clientOperationId,
                    operationType,
                    amountMinor,
                    currency,
                    OperationStatus.SUCCEEDED,
                    effectiveScenario,
                    now,
                    now
            );

            // Schedule webhooks based on scenario
            scheduleWebhooks(operation, effectiveScenario, scenarioConfig.delayMs(), webhookUrl, now);

            // Clear scenario configuration after successful operation execution
            scenarioRegistry.clear(clientOperationId);

            log.info("Provider operation created: id={}, clientOperationId={}, scenario={}",
                    operationId, clientOperationId, effectiveScenario);

            return new OperationExecutionResult(operation, false, effectiveScenario, scenarioConfig.delayMs());
        } else {
            // Replay detected
            ProviderOperation existing = operationRepository.findByClientOperationId(clientOperationId)
                    .orElseThrow(() -> new IllegalStateException("Expected existing provider operation for: " + clientOperationId));

            // Validate idempotency identity fields
            if (existing.getOperationType() != operationType
                    || existing.getAmountMinor() != amountMinor
                    || !existing.getCurrency().equals(currency)) {
                log.warn("Conflicting parameters for existing clientOperationId: {}", clientOperationId);
                throw new ConflictingReplayException("Conflicting parameters for clientOperationId: " + clientOperationId);
            }

            // Validate durable webhook target URL identity
            List<ProviderWebhook> existingWebhooks = webhookRepository.findByProviderOperationId(existing.getId());
            String existingTargetUrl = existingWebhooks.isEmpty() ? null : existingWebhooks.get(0).getTargetUrl();
            if (!Objects.equals(existingTargetUrl, webhookUrl)) {
                log.warn("Conflicting webhookUrl for existing clientOperationId: {}, existing: '{}', requested: '{}'",
                        clientOperationId, existingTargetUrl, webhookUrl);
                throw new ConflictingReplayException("Conflicting webhookUrl for clientOperationId: " + clientOperationId);
            }

            log.info("Idempotent replay for clientOperationId: {}, existing id: {}", clientOperationId, existing.getId());
            return new OperationExecutionResult(existing, true, SimulatorScenario.NORMAL_SUCCESS, 0);
        }
    }

    private void scheduleWebhooks(
            ProviderOperation operation,
            SimulatorScenario scenario,
            long delayMs,
            String webhookUrl,
            Instant now
    ) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        UUID logicalEventId = UUID.randomUUID();
        String payloadJson = buildWebhookPayload(logicalEventId, operation, now);

        if (scenario == SimulatorScenario.DUPLICATE_WEBHOOK) {
            // Schedule two delivery rows for the exact same logical eventId
            ProviderWebhook delivery1 = new ProviderWebhook(
                    UUID.randomUUID(),
                    logicalEventId,
                    operation,
                    1,
                    "PROVIDER_OPERATION_SUCCEEDED",
                    payloadJson,
                    webhookUrl,
                    WebhookStatus.SCHEDULED,
                    now,
                    null,
                    now
            );
            ProviderWebhook delivery2 = new ProviderWebhook(
                    UUID.randomUUID(),
                    logicalEventId,
                    operation,
                    2,
                    "PROVIDER_OPERATION_SUCCEEDED",
                    payloadJson,
                    webhookUrl,
                    WebhookStatus.SCHEDULED,
                    now,
                    null,
                    now
            );
            webhookRepository.saveAll(List.of(delivery1, delivery2));
            log.info("Scheduled DUPLICATE_WEBHOOK: logicalEventId={}, operationId={}", logicalEventId, operation.getId());
        } else {
            Instant scheduledAt = (scenario == SimulatorScenario.DELAYED_WEBHOOK) ? now.plusMillis(delayMs) : now;
            ProviderWebhook delivery = new ProviderWebhook(
                    UUID.randomUUID(),
                    logicalEventId,
                    operation,
                    1,
                    "PROVIDER_OPERATION_SUCCEEDED",
                    payloadJson,
                    webhookUrl,
                    WebhookStatus.SCHEDULED,
                    scheduledAt,
                    null,
                    now
            );
            webhookRepository.save(delivery);
            log.info("Scheduled webhook: id={}, logicalEventId={}, scheduledAt={}", delivery.getId(), logicalEventId, scheduledAt);
        }
    }

    private String buildWebhookPayload(UUID eventId, ProviderOperation operation, Instant occurredAt) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", eventId.toString());
        map.put("eventSequence", 1);
        map.put("eventType", "PROVIDER_OPERATION_SUCCEEDED");
        map.put("providerOperationId", operation.getId().toString());
        map.put("clientOperationId", operation.getClientOperationId().toString());
        map.put("operationType", operation.getOperationType().name());
        map.put("status", operation.getStatus().name());
        map.put("amountMinor", String.valueOf(operation.getAmountMinor()));
        map.put("currency", operation.getCurrency());
        map.put("occurredAt", occurredAt.toString());

        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize webhook payload", e);
        }
    }

    @Transactional(readOnly = true)
    public ProviderOperation getById(UUID id) {
        return operationRepository.findById(id)
                .orElseThrow(() -> new OperationNotFoundException("Provider operation not found: " + id));
    }

    @Transactional(readOnly = true)
    public ProviderOperation getByClientOperationId(UUID clientOperationId) {
        return operationRepository.findByClientOperationId(clientOperationId)
                .orElseThrow(() -> new OperationNotFoundException("Provider operation not found for clientOperationId: " + clientOperationId));
    }
}
