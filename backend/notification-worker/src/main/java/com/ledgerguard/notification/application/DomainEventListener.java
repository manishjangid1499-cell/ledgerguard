package com.ledgerguard.notification.application;

import com.ledgerguard.notification.domain.IncomingDomainEvent;
import com.ledgerguard.notification.domain.ProcessingOutcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class DomainEventListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListener.class);

    private static final String EXPECTED_SPEC_VERSION = "1.0";
    private static final String EXPECTED_SOURCE = "urn:ledgerguard:ledgerguard-api";
    private static final String EXPECTED_CONTENT_TYPE = "application/json";
    private static final int SUPPORTED_EVENT_VERSION = 1;

    private static final String TYPE_TRANSFER_COMPLETED = "TRANSFER_COMPLETED";
    private static final String TYPE_PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    private static final String TYPE_REFUND_COMPLETED = "REFUND_COMPLETED";

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            TYPE_TRANSFER_COMPLETED,
            TYPE_PAYMENT_SUCCEEDED,
            TYPE_REFUND_COMPLETED
    );

    private final NotificationProcessingService processingService;
    private final ObjectMapper objectMapper;

    public DomainEventListener(
            NotificationProcessingService processingService,
            ObjectMapper objectMapper
    ) {
        this.processingService = Objects.requireNonNull(processingService, "processingService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @KafkaListener(
            topics = "${ledgerguard.kafka.domain-events-topic:ledgerguard.domain-events.v1}",
            groupId = "${spring.kafka.consumer.group-id:ledgerguard-notification-worker-v1}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onDomainEvent(ConsumerRecord<String, String> record) {
        String rawMessage = record.value();
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new InvalidDomainEventException("Received empty or blank domain event message");
        }

        IncomingDomainEvent event = parseAndValidateEnvelope(rawMessage, record.key());
        ProcessingOutcome outcome = processingService.processEvent(event);

        log.debug("Completed domain event processing: eventId={}, outcome={}", event.eventId(), outcome);
    }

    private IncomingDomainEvent parseAndValidateEnvelope(String rawMessage, String recordKey) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawMessage);
        } catch (Exception e) {
            throw new InvalidDomainEventException("Failed to parse CloudEvents JSON payload", e);
        }

        if (root == null || !root.isObject()) {
            throw new InvalidDomainEventException("CloudEvents payload root must be a JSON object");
        }

        // Validate specversion
        JsonNode specVersionNode = root.get("specversion");
        if (specVersionNode == null || !EXPECTED_SPEC_VERSION.equals(specVersionNode.asText())) {
            throw new InvalidDomainEventException("Invalid specversion: " + (specVersionNode != null ? specVersionNode.asText() : "null"));
        }

        // Validate source
        JsonNode sourceNode = root.get("source");
        if (sourceNode == null || !EXPECTED_SOURCE.equals(sourceNode.asText())) {
            throw new InvalidDomainEventException("Untrusted or invalid event source: " + (sourceNode != null ? sourceNode.asText() : "null"));
        }

        // Validate datacontenttype
        JsonNode contentTypeNode = root.get("datacontenttype");
        if (contentTypeNode != null && !EXPECTED_CONTENT_TYPE.equalsIgnoreCase(contentTypeNode.asText())) {
            throw new InvalidDomainEventException("Unsupported datacontenttype: " + contentTypeNode.asText());
        }

        // Validate event id
        JsonNode idNode = root.get("id");
        if (idNode == null || idNode.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing required CloudEvents 'id'");
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(idNode.asText());
        } catch (IllegalArgumentException e) {
            throw new InvalidDomainEventException("Invalid UUID format for 'id': " + idNode.asText(), e);
        }

        // Validate time
        JsonNode timeNode = root.get("time");
        if (timeNode == null || timeNode.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing required CloudEvents 'time'");
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(timeNode.asText());
        } catch (Exception e) {
            throw new InvalidDomainEventException("Invalid ISO-8601 timestamp in 'time': " + timeNode.asText(), e);
        }

        // Validate event type
        JsonNode typeNode = root.get("type");
        if (typeNode == null || typeNode.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing required CloudEvents 'type'");
        }
        String eventType = typeNode.asText();
        if (!SUPPORTED_EVENT_TYPES.contains(eventType)) {
            throw new UnsupportedEventTypeException("Unsupported event type: " + eventType);
        }

        // Validate event version
        JsonNode versionNode = root.get("eventversion");
        if (versionNode == null || !versionNode.isInt()) {
            throw new InvalidDomainEventException("Missing or invalid integer 'eventversion'");
        }
        int eventVersion = versionNode.asInt();
        if (eventVersion != SUPPORTED_EVENT_VERSION) {
            throw new UnsupportedEventVersionException("Unsupported event version: " + eventVersion);
        }

        // Validate aggregate type
        JsonNode aggTypeNode = root.get("aggregatetype");
        if (aggTypeNode == null || aggTypeNode.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing required 'aggregatetype'");
        }
        String aggregateType = aggTypeNode.asText();

        // Validate aggregate id
        JsonNode aggIdNode = root.get("aggregateid");
        if (aggIdNode == null || aggIdNode.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing required 'aggregateid'");
        }
        UUID aggregateId;
        try {
            aggregateId = UUID.fromString(aggIdNode.asText());
        } catch (IllegalArgumentException e) {
            throw new InvalidDomainEventException("Invalid UUID format for 'aggregateid': " + aggIdNode.asText(), e);
        }

        // Validate event type / aggregate type consistency
        validateTypeAggregateConsistency(eventType, aggregateType);

        // Validate subject consistency
        JsonNode subjectNode = root.get("subject");
        if (subjectNode == null || subjectNode.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing required CloudEvents 'subject'");
        }
        String expectedSubject = aggregateType + "/" + aggregateId;
        if (!expectedSubject.equals(subjectNode.asText())) {
            throw new InvalidDomainEventException("Inconsistent subject: expected " + expectedSubject + " but got " + subjectNode.asText());
        }

        // Validate data payload
        JsonNode dataNode = root.get("data");
        if (dataNode == null || !dataNode.isObject()) {
            throw new InvalidDomainEventException("Missing or invalid 'data' payload object");
        }

        validateEventPayload(eventType, aggregateId, dataNode);

        return new IncomingDomainEvent(
                eventId,
                eventType,
                eventVersion,
                aggregateType,
                aggregateId,
                occurredAt,
                dataNode
        );
    }

    private void validateTypeAggregateConsistency(String eventType, String aggregateType) {
        switch (eventType) {
            case TYPE_TRANSFER_COMPLETED -> {
                if (!"TRANSFER".equals(aggregateType)) {
                    throw new InvalidDomainEventException("Mismatched aggregateType '" + aggregateType + "' for " + eventType);
                }
            }
            case TYPE_PAYMENT_SUCCEEDED -> {
                if (!"PAYMENT".equals(aggregateType)) {
                    throw new InvalidDomainEventException("Mismatched aggregateType '" + aggregateType + "' for " + eventType);
                }
            }
            case TYPE_REFUND_COMPLETED -> {
                if (!"REFUND".equals(aggregateType)) {
                    throw new InvalidDomainEventException("Mismatched aggregateType '" + aggregateType + "' for " + eventType);
                }
            }
        }
    }

    private void validateEventPayload(String eventType, UUID aggregateId, JsonNode data) {
        switch (eventType) {
            case TYPE_TRANSFER_COMPLETED -> {
                UUID transferId = validateRequiredUuid(data, "transferId");
                if (!transferId.equals(aggregateId)) {
                    throw new InvalidDomainEventException("Payload transferId (" + transferId + ") does not match aggregateId (" + aggregateId + ")");
                }
                validateRequiredUuid(data, "sourceLedgerAccountId");
                validateRequiredUuid(data, "destinationLedgerAccountId");
                validateMoneyString(data, "amountMinor");
                validateRequiredCurrency(data, "currency");
                validateRequiredUuid(data, "journalTransactionId");
            }
            case TYPE_PAYMENT_SUCCEEDED -> {
                UUID paymentId = validateRequiredUuid(data, "paymentId");
                if (!paymentId.equals(aggregateId)) {
                    throw new InvalidDomainEventException("Payload paymentId (" + paymentId + ") does not match aggregateId (" + aggregateId + ")");
                }
                validateRequiredUuid(data, "customerLedgerAccountId");
                validateRequiredUuid(data, "merchantLedgerAccountId");
                validateMoneyString(data, "grossAmountMinor");
                validateMoneyString(data, "feeAmountMinor");
                validateMoneyString(data, "merchantNetAmountMinor");
                validateRequiredCurrency(data, "currency");
                validateRequiredUuid(data, "journalTransactionId");
            }
            case TYPE_REFUND_COMPLETED -> {
                UUID refundId = validateRequiredUuid(data, "refundId");
                if (!refundId.equals(aggregateId)) {
                    throw new InvalidDomainEventException("Payload refundId (" + refundId + ") does not match aggregateId (" + aggregateId + ")");
                }
                validateRequiredUuid(data, "paymentId");
                validateMoneyString(data, "refundAmountMinor");
                validateMoneyString(data, "merchantDebitAmountMinor");
                validateMoneyString(data, "feeDebitAmountMinor");
                validateRequiredCurrency(data, "currency");
                validateRequiredUuid(data, "journalTransactionId");
            }
            default -> throw new UnsupportedEventTypeException("Unexpected event type validation: " + eventType);
        }
    }

    private UUID validateRequiredUuid(JsonNode data, String fieldName) {
        JsonNode node = data.get(fieldName);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing or blank required UUID field in data: " + fieldName);
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new InvalidDomainEventException("Invalid UUID format in payload field '" + fieldName + "': " + node.asText(), e);
        }
    }

    private void validateRequiredCurrency(JsonNode data, String fieldName) {
        JsonNode node = data.get(fieldName);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing or blank required currency field in data: " + fieldName);
        }
        if (!"INR".equals(node.asText())) {
            throw new InvalidDomainEventException("Unsupported currency: " + node.asText() + ", expected 'INR'");
        }
    }

    private void validateMoneyString(JsonNode data, String fieldName) {
        JsonNode node = data.get(fieldName);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new InvalidDomainEventException("Missing or non-string monetary field in data: " + fieldName);
        }
        try {
            new BigInteger(node.asText());
        } catch (NumberFormatException e) {
            throw new InvalidDomainEventException("Monetary field '" + fieldName + "' is not a valid integer minor amount string: " + node.asText(), e);
        }
    }
}
