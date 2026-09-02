package com.ledgerguard.provider.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ledgerguard.provider.domain.ProviderEvent;
import com.ledgerguard.provider.domain.ProviderEventPayload;
import com.ledgerguard.provider.infrastructure.ProviderEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Service
public class ProviderEventIngressService {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventIngressService.class);

    private final ProviderEventRepository providerEventRepository;
    private final ObjectMapper objectMapper;

    public ProviderEventIngressService(
            ProviderEventRepository providerEventRepository,
            ObjectMapper objectMapper
    ) {
        this.providerEventRepository = providerEventRepository;
        this.objectMapper = objectMapper;
    }

    public record IngressResult(ProviderEvent event, boolean isDuplicate) {}

    @Transactional(propagation = Propagation.REQUIRED)
    public IngressResult recordEvent(ProviderEventPayload payload) {
        long amountMinor = Long.parseLong(payload.amountMinor());
        Instant now = Instant.now();

        // Conflict-safe atomic insert with no conflict target
        int inserted = providerEventRepository.tryInsertEvent(
                payload.eventId(),
                payload.providerOperationId(),
                payload.clientOperationId(),
                payload.eventSequence(),
                payload.eventType(),
                payload.operationType(),
                payload.status(),
                amountMinor,
                payload.currency(),
                Timestamp.from(payload.occurredAt()),
                payload.rawJson(),
                Timestamp.from(now)
        );

        if (inserted == 1) {
            ProviderEvent created = providerEventRepository.findById(payload.eventId())
                    .orElseThrow(() -> new IllegalStateException("Expected created ProviderEvent for: " + payload.eventId()));
            log.info("Recorded fresh ProviderEvent: eventId={}, providerOpId={}, seq={}, type={}, status={}",
                    created.getEventId(), created.getProviderOperationId(), created.getEventSequence(),
                    created.getEventType(), created.getProviderStatus());
            return new IngressResult(created, false);
        }

        // inserted == 0: conflict on event_id or (provider_operation_id, event_sequence)
        // Step A: Check by event_id
        Optional<ProviderEvent> byId = providerEventRepository.findById(payload.eventId());
        if (byId.isPresent()) {
            ProviderEvent existing = byId.get();
            boolean matches = existing.getProviderOperationId().equals(payload.providerOperationId())
                    && existing.getClientOperationId().equals(payload.clientOperationId())
                    && existing.getEventSequence() == payload.eventSequence()
                    && existing.getEventType().equals(payload.eventType())
                    && existing.getOperationType().equalsIgnoreCase(payload.operationType())
                    && existing.getProviderStatus().equalsIgnoreCase(payload.status())
                    && existing.getAmountMinor() == amountMinor
                    && existing.getCurrency().equalsIgnoreCase(payload.currency());

            if (matches) {
                try {
                    JsonNode existingJson = objectMapper.readTree(existing.getPayload());
                    JsonNode incomingJson = objectMapper.readTree(payload.rawJson());
                    if (existingJson.equals(incomingJson)) {
                        log.info("Idempotent duplicate ProviderEvent received: eventId={}", existing.getEventId());
                        return new IngressResult(existing, true);
                    }
                } catch (Exception ex) {
                    throw new ProviderEventConflictException("Failed to compare semantic JSON for eventId: " + payload.eventId());
                }
            }

            log.warn("Conflict for existing eventId {}: material content mismatch", payload.eventId());
            throw new ProviderEventConflictException("Existing eventId with conflicting payload content: " + payload.eventId());
        }

        // Step B: Check by (provider_operation_id, event_sequence)
        Optional<ProviderEvent> bySeq = providerEventRepository.findByProviderOperationIdAndEventSequence(
                payload.providerOperationId(), payload.eventSequence());
        if (bySeq.isPresent()) {
            log.warn("Sequence ownership conflict: providerOpId={}, seq={}, existingEventId={}, incomingEventId={}",
                    payload.providerOperationId(), payload.eventSequence(), bySeq.get().getEventId(), payload.eventId());
            throw new ProviderEventConflictException("Sequence ownership conflict: sequence " + payload.eventSequence()
                    + " for providerOperationId " + payload.providerOperationId() + " already owned by event " + bySeq.get().getEventId());
        }

        // Step C: Unexpected state
        throw new IllegalStateException("Atomic insert returned 0 but no conflicting row found for eventId=" + payload.eventId());
    }
}
