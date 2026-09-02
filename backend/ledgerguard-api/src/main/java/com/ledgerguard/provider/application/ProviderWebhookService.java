package com.ledgerguard.provider.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ledgerguard.provider.domain.ProviderEventPayload;
import com.ledgerguard.provider.infrastructure.HmacSignatureValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Service
public class ProviderWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookService.class);

    private final HmacSignatureValidator signatureValidator;
    private final ProviderEventIngressService ingressService;
    private final ProviderEventProcessingService processingService;
    private final ObjectMapper objectMapper;

    public enum WebhookStatusResult {
        OK,
        ACCEPTED
    }

    public ProviderWebhookService(
            HmacSignatureValidator signatureValidator,
            ProviderEventIngressService ingressService,
            ProviderEventProcessingService processingService,
            ObjectMapper objectMapper
    ) {
        this.signatureValidator = signatureValidator;
        this.ingressService = ingressService;
        this.processingService = processingService;
        this.objectMapper = objectMapper;
    }

    public WebhookStatusResult handleWebhook(String timestampHeader, String signatureHeader, byte[] rawBody) {
        // Step 1: Verify HMAC signature and timestamp over exact raw request body bytes
        signatureValidator.validate(timestampHeader, signatureHeader, rawBody);

        // Step 2: Parse and validate JSON payload ONLY after HMAC authentication passes
        ProviderEventPayload payload = parsePayload(rawBody);

        // Step 3: Record event atomically in durable inbox (Phase B commit)
        ProviderEventIngressService.IngressResult ingressResult = ingressService.recordEvent(payload);

        // Step 4: Process contiguous pending events (Phase C commit)
        ProviderEventProcessingService.ProcessingOutcome outcome =
                processingService.processPendingEvents(payload.providerOperationId());

        if (outcome.hasPendingGap() || outcome.localOperationMissing()) {
            log.info("Webhook acknowledged as ACCEPTED (pending gap or local operation): eventId={}, gap={}, missingLocal={}",
                    payload.eventId(), outcome.hasPendingGap(), outcome.localOperationMissing());
            return WebhookStatusResult.ACCEPTED;
        }

        return WebhookStatusResult.OK;
    }

    private ProviderEventPayload parsePayload(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new ProviderEventValidationException("Webhook request body cannot be empty");
        }

        String rawJson = new String(rawBody, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            throw new ProviderEventValidationException("Malformed JSON in authenticated webhook body: " + ex.getMessage());
        }

        if (!root.isObject()) {
            throw new ProviderEventValidationException("Webhook JSON payload must be an object");
        }

        UUID eventId = parseUuid(root, "eventId");
        long eventSequence = parsePositiveLong(root, "eventSequence");
        String eventType = parseRequiredString(root, "eventType");
        UUID providerOperationId = parseUuid(root, "providerOperationId");
        UUID clientOperationId = parseUuid(root, "clientOperationId");
        String operationType = parseRequiredString(root, "operationType").toUpperCase();
        String status = parseRequiredString(root, "status").toUpperCase();
        String amountMinorStr = parseRequiredString(root, "amountMinor");
        String currency = parseRequiredString(root, "currency");
        Instant occurredAt = parseInstant(root, "occurredAt");

        // Validate constraints
        if (!"CREDIT".equals(operationType) && !"DEBIT".equals(operationType)) {
            throw new ProviderEventValidationException("Invalid operationType: " + operationType);
        }
        if (!"PROCESSING".equals(status) && !"SUCCEEDED".equals(status) && !"FAILED".equals(status)) {
            throw new ProviderEventValidationException("Invalid provider status: " + status);
        }
        if (!"INR".equalsIgnoreCase(currency)) {
            throw new ProviderEventValidationException("Invalid currency (must be INR): " + currency);
        }

        // Event type and status match
        if ("PROCESSING".equals(status) && !"PROVIDER_OPERATION_PROCESSING".equals(eventType)) {
            throw new ProviderEventValidationException("eventType mismatch for PROCESSING status: " + eventType);
        }
        if ("SUCCEEDED".equals(status) && !"PROVIDER_OPERATION_SUCCEEDED".equals(eventType)) {
            throw new ProviderEventValidationException("eventType mismatch for SUCCEEDED status: " + eventType);
        }
        if ("FAILED".equals(status) && !"PROVIDER_OPERATION_FAILED".equals(eventType)) {
            throw new ProviderEventValidationException("eventType mismatch for FAILED status: " + eventType);
        }

        try {
            long amt = Long.parseLong(amountMinorStr);
            if (amt <= 0) {
                throw new ProviderEventValidationException("amountMinor must be positive: " + amountMinorStr);
            }
        } catch (NumberFormatException ex) {
            throw new ProviderEventValidationException("Invalid amountMinor: " + amountMinorStr);
        }

        return new ProviderEventPayload(
                eventId,
                eventSequence,
                eventType,
                providerOperationId,
                clientOperationId,
                operationType,
                status,
                amountMinorStr,
                currency,
                occurredAt,
                rawJson
        );
    }

    private UUID parseUuid(JsonNode root, String field) {
        String val = parseRequiredString(root, field);
        try {
            return UUID.fromString(val);
        } catch (IllegalArgumentException ex) {
            throw new ProviderEventValidationException("Field " + field + " must be a valid UUID: " + val);
        }
    }

    private long parsePositiveLong(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isNumber()) {
            throw new ProviderEventValidationException("Field " + field + " must be a positive number");
        }
        long val = node.asLong();
        if (val <= 0) {
            throw new ProviderEventValidationException("Field " + field + " must be > 0: " + val);
        }
        return val;
    }

    private String parseRequiredString(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ProviderEventValidationException("Field " + field + " is required and must be non-empty");
        }
        return node.asText().trim();
    }

    private Instant parseInstant(JsonNode root, String field) {
        String val = parseRequiredString(root, field);
        try {
            return Instant.parse(val);
        } catch (Exception ex) {
            throw new ProviderEventValidationException("Field " + field + " must be a valid ISO-8601 timestamp: " + val);
        }
    }
}
