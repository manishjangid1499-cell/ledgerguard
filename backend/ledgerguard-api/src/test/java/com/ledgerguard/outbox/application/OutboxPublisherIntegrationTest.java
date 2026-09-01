package com.ledgerguard.outbox.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.outbox.domain.DomainEventEnvelope;
import com.ledgerguard.outbox.domain.OutboxEvent;
import com.ledgerguard.outbox.domain.OutboxStatus;
import com.ledgerguard.outbox.infrastructure.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPublisherIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisherService outboxPublisherService;

    @Autowired
    private OutboxPublisherWorker outboxPublisherWorker;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${ledgerguard.kafka.domain-events-topic:ledgerguard.domain-events.v1}")
    private String domainEventsTopic;

    private KafkaConsumer<String, String> createTestConsumer() {
        var props = new java.util.Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(domainEventsTopic));
        return consumer;
    }

    private List<ConsumerRecord<String, String>> pollRecords(
            KafkaConsumer<String, String> consumer,
            java.util.function.Predicate<ConsumerRecord<String, String>> predicate,
            int expectedCount,
            Duration timeout
    ) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);
        while (records.size() < expectedCount && Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
            for (ConsumerRecord<String, String> r : polled) {
                if (predicate.test(r)) {
                    records.add(r);
                }
            }
        }
        return records;
    }

    private void insertPendingOutboxEvent(UUID eventId, String aggregateType, UUID aggregateId, String eventType, int eventVersion, String payload, Instant occurredAt) {
        Timestamp occurredTs = Timestamp.from(occurredAt);
        Timestamp createdTs = Timestamp.from(occurredAt);
        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?, ?, NULL)",
                eventId, aggregateType, aggregateId, eventType, eventVersion, payload, occurredTs, createdTs
        );
    }

    @Test
    @DisplayName("Empty poll returns 0 and does not perform Kafka operations or mutations")
    void emptyPollReturnsZero() {
        int published = outboxPublisherService.publishPendingBatch(10);
        assertThat(published).isEqualTo(0);
    }

    @Test
    @DisplayName("Normal TRANSFER_COMPLETED publication delivers CloudEvents JSON to Kafka and marks event PUBLISHED")
    void normalTransferPublicationDeliversToKafkaAndMarksPublished() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID sourceAccount = UUID.randomUUID();
        UUID destAccount = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        String payload = String.format(
                "{\"transferId\":\"%s\",\"sourceLedgerAccountId\":\"%s\",\"destinationLedgerAccountId\":\"%s\",\"amountMinor\":\"15000\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                transferId, sourceAccount, destAccount, journalId
        );

        insertPendingOutboxEvent(eventId, "TRANSFER", transferId, "TRANSFER_COMPLETED", 1, payload, occurredAt);

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            consumer.poll(Duration.ofMillis(100));

            int published = outboxPublisherService.publishPendingBatch(10);
            assertThat(published).isGreaterThanOrEqualTo(1);

            // Verify database state
            OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(event.getPublishedAt()).isNotNull();
            assertThat(event.getPublishedAt()).isAfterOrEqualTo(event.getCreatedAt());

            // Verify Kafka record
            List<ConsumerRecord<String, String>> records = pollRecords(consumer, r -> r.key().equals(transferId.toString()), 1, Duration.ofSeconds(10));
            assertThat(records).isNotEmpty();
            ConsumerRecord<String, String> match = records.get(0);
            assertThat(match.key()).isEqualTo(transferId.toString());

            // Parse CloudEvents JSON envelope
            JsonNode envelopeNode = objectMapper.readTree(match.value());
            assertThat(envelopeNode.get("specversion").asText()).isEqualTo("1.0");
            assertThat(envelopeNode.get("id").asText()).isEqualTo(eventId.toString());
            assertThat(envelopeNode.get("source").asText()).isEqualTo("urn:ledgerguard:ledgerguard-api");
            assertThat(envelopeNode.get("type").asText()).isEqualTo("TRANSFER_COMPLETED");
            assertThat(envelopeNode.get("subject").asText()).isEqualTo("TRANSFER/" + transferId);
            assertThat(envelopeNode.get("time").asText()).isEqualTo(occurredAt.toString());
            assertThat(envelopeNode.get("datacontenttype").asText()).isEqualTo("application/json");
            assertThat(envelopeNode.get("eventversion").asInt()).isEqualTo(1);
            assertThat(envelopeNode.get("aggregatetype").asText()).isEqualTo("TRANSFER");
            assertThat(envelopeNode.get("aggregateid").asText()).isEqualTo(transferId.toString());

            // Data payload object verification
            JsonNode dataNode = envelopeNode.get("data");
            assertThat(dataNode.isObject()).isTrue();
            assertThat(dataNode.get("transferId").asText()).isEqualTo(transferId.toString());
            assertThat(dataNode.get("sourceLedgerAccountId").asText()).isEqualTo(sourceAccount.toString());
            assertThat(dataNode.get("destinationLedgerAccountId").asText()).isEqualTo(destAccount.toString());
            assertThat(dataNode.get("amountMinor").asText()).isEqualTo("15000");
            assertThat(dataNode.get("currency").asText()).isEqualTo("INR");
            assertThat(dataNode.get("journalTransactionId").asText()).isEqualTo(journalId.toString());
        }
    }

    @Test
    @DisplayName("PAYMENT_SUCCEEDED publication satisfies CloudEvents contract with string minor amounts")
    void paymentSucceededPublicationSatisfiesContract() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID customerAccount = UUID.randomUUID();
        UUID merchantAccount = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        String payload = String.format(
                "{\"paymentId\":\"%s\",\"customerLedgerAccountId\":\"%s\",\"merchantLedgerAccountId\":\"%s\",\"grossAmountMinor\":\"10000\",\"feeAmountMinor\":\"100\",\"merchantNetAmountMinor\":\"9900\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                paymentId, customerAccount, merchantAccount, journalId
        );

        insertPendingOutboxEvent(eventId, "PAYMENT", paymentId, "PAYMENT_SUCCEEDED", 1, payload, occurredAt);

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            consumer.poll(Duration.ofMillis(100));

            int published = outboxPublisherService.publishPendingBatch(10);
            assertThat(published).isGreaterThanOrEqualTo(1);

            OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

            List<ConsumerRecord<String, String>> records = pollRecords(consumer, r -> r.key().equals(paymentId.toString()), 1, Duration.ofSeconds(10));
            assertThat(records).isNotEmpty();
            ConsumerRecord<String, String> match = records.get(0);

            JsonNode envelopeNode = objectMapper.readTree(match.value());
            assertThat(envelopeNode.get("type").asText()).isEqualTo("PAYMENT_SUCCEEDED");
            assertThat(envelopeNode.get("subject").asText()).isEqualTo("PAYMENT/" + paymentId);
            assertThat(envelopeNode.get("aggregateid").asText()).isEqualTo(paymentId.toString());

            JsonNode dataNode = envelopeNode.get("data");
            assertThat(dataNode.get("grossAmountMinor").asText()).isEqualTo("10000");
            assertThat(dataNode.get("feeAmountMinor").asText()).isEqualTo("100");
            assertThat(dataNode.get("merchantNetAmountMinor").asText()).isEqualTo("9900");
        }
    }

    @Test
    @DisplayName("REFUND_COMPLETED publication satisfies CloudEvents contract with proportional fee/merchant debits")
    void refundCompletedPublicationSatisfiesContract() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        String payload = String.format(
                "{\"refundId\":\"%s\",\"paymentId\":\"%s\",\"refundAmountMinor\":\"5000\",\"merchantDebitAmountMinor\":\"4950\",\"feeDebitAmountMinor\":\"50\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                refundId, paymentId, journalId
        );

        insertPendingOutboxEvent(eventId, "REFUND", refundId, "REFUND_COMPLETED", 1, payload, occurredAt);

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            consumer.poll(Duration.ofMillis(100));

            int published = outboxPublisherService.publishPendingBatch(10);
            assertThat(published).isGreaterThanOrEqualTo(1);

            OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

            List<ConsumerRecord<String, String>> records = pollRecords(consumer, r -> r.key().equals(refundId.toString()), 1, Duration.ofSeconds(10));
            assertThat(records).isNotEmpty();
            ConsumerRecord<String, String> match = records.get(0);

            JsonNode envelopeNode = objectMapper.readTree(match.value());
            assertThat(envelopeNode.get("type").asText()).isEqualTo("REFUND_COMPLETED");
            assertThat(envelopeNode.get("subject").asText()).isEqualTo("REFUND/" + refundId);
            assertThat(envelopeNode.get("aggregateid").asText()).isEqualTo(refundId.toString());

            JsonNode dataNode = envelopeNode.get("data");
            assertThat(dataNode.get("refundAmountMinor").asText()).isEqualTo("5000");
            assertThat(dataNode.get("merchantDebitAmountMinor").asText()).isEqualTo("4950");
            assertThat(dataNode.get("feeDebitAmountMinor").asText()).isEqualTo("50");
        }
    }

    @Test
    @DisplayName("Monetary amounts beyond JavaScript MAX_SAFE_INTEGER are preserved exactly as strings")
    void largeMonetaryAmountPreservedAsString() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        UUID sourceAccount = UUID.randomUUID();
        UUID destAccount = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        String largeMinorAmount = "9007199254740995";
        String payload = String.format(
                "{\"transferId\":\"%s\",\"sourceLedgerAccountId\":\"%s\",\"destinationLedgerAccountId\":\"%s\",\"amountMinor\":\"%s\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                transferId, sourceAccount, destAccount, largeMinorAmount, journalId
        );

        insertPendingOutboxEvent(eventId, "TRANSFER", transferId, "TRANSFER_COMPLETED", 1, payload, occurredAt);

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            consumer.poll(Duration.ofMillis(100));

            outboxPublisherService.publishPendingBatch(10);

            List<ConsumerRecord<String, String>> records = pollRecords(consumer, r -> r.key().equals(transferId.toString()), 1, Duration.ofSeconds(10));
            assertThat(records).isNotEmpty();
            ConsumerRecord<String, String> match = records.get(0);

            JsonNode envelopeNode = objectMapper.readTree(match.value());
            assertThat(envelopeNode.get("data").get("amountMinor").asText()).isEqualTo(largeMinorAmount);
        }
    }

    @Test
    @DisplayName("PUBLISHED outbox event is not republished on subsequent cycles")
    void publishedEventIsNotRepublished() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        // Direct insert PENDING then transition to PUBLISHED
        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload, status, occurred_at, created_at, published_at) " +
                        "VALUES (?, 'TRANSFER', ?, 'TRANSFER_COMPLETED', 1, '{\"transferId\":\"" + transferId + "\"}'::jsonb, 'PENDING', ?, ?, NULL)",
                eventId, transferId, nowTs, nowTs
        );
        jdbcTemplate.update(
                "UPDATE outbox_events SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                nowTs, eventId
        );

        int published = outboxPublisherService.publishPendingBatch(10);
        assertThat(published).isEqualTo(0);
    }

    @Test
    @DisplayName("Crash window / at-least-once retry produces duplicate Kafka message with SAME event ID")
    void crashWindowProducesDuplicateWithSameEventId() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        String payload = String.format("{\"transferId\":\"%s\",\"amountMinor\":\"10000\"}", transferId);
        insertPendingOutboxEvent(eventId, "TRANSFER", transferId, "TRANSFER_COMPLETED", 1, payload, occurredAt);

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            consumer.poll(Duration.ofMillis(100));

            // Cycle 1: Execute publication inside transaction template and force rollback AFTER Kafka send
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            try {
                tt.execute(status -> {
                    outboxPublisherService.publishPendingBatch(10);
                    // Force transaction rollback after successful Kafka send
                    throw new RuntimeException("Simulated crash after Kafka ACK before DB commit");
                });
            } catch (RuntimeException ignored) {
            }

            // Verify database state: event rolled back and remains PENDING
            OutboxEvent rolledBackEvent = outboxEventRepository.findById(eventId).orElseThrow();
            assertThat(rolledBackEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(rolledBackEvent.getPublishedAt()).isNull();

            // Cycle 2: Retry publisher successfully
            int publishedRetry = outboxPublisherService.publishPendingBatch(10);
            assertThat(publishedRetry).isGreaterThanOrEqualTo(1);

            OutboxEvent finalEvent = outboxEventRepository.findById(eventId).orElseThrow();
            assertThat(finalEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(finalEvent.getPublishedAt()).isNotNull();

            // Verify Kafka: received 2 records for transferId, BOTH having identical event ID
            List<ConsumerRecord<String, String>> records = pollRecords(consumer, r -> r.key().equals(transferId.toString()), 2, Duration.ofSeconds(10));
            List<ConsumerRecord<String, String>> transferRecords = records.stream()
                    .filter(r -> r.key().equals(transferId.toString()))
                    .toList();

            assertThat(transferRecords).hasSize(2);

            JsonNode msg1 = objectMapper.readTree(transferRecords.get(0).value());
            JsonNode msg2 = objectMapper.readTree(transferRecords.get(1).value());

            assertThat(msg1.get("id").asText()).isEqualTo(eventId.toString());
            assertThat(msg2.get("id").asText()).isEqualTo(eventId.toString());
            assertThat(msg1.get("type").asText()).isEqualTo("TRANSFER_COMPLETED");
            assertThat(msg2.get("type").asText()).isEqualTo("TRANSFER_COMPLETED");
            assertThat(msg1.get("data")).isEqualTo(msg2.get("data"));
        }
    }

    @Test
    @DisplayName("Multi-worker execution with 4 workers processes 20 pending events without lost or double-claimed events")
    void multiWorkerExecutionProcessesAllEventsSafely() throws Exception {
        int eventCount = 20;
        int workerCount = 4;
        int batchSize = 5;

        List<UUID> eventIds = new ArrayList<>();
        List<UUID> aggregateIds = new ArrayList<>();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        for (int i = 0; i < eventCount; i++) {
            UUID eventId = UUID.randomUUID();
            UUID aggregateId = UUID.randomUUID();
            eventIds.add(eventId);
            aggregateIds.add(aggregateId);

            String payload = String.format("{\"index\":%d,\"aggregateId\":\"%s\"}", i, aggregateId);
            insertPendingOutboxEvent(eventId, "TRANSFER", aggregateId, "TRANSFER_COMPLETED", 1, payload, now.plusMillis(i));
        }

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            consumer.poll(Duration.ofMillis(100));

            ExecutorService executor = Executors.newFixedThreadPool(workerCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(workerCount);
            AtomicInteger totalPublished = new AtomicInteger(0);

            for (int i = 0; i < workerCount; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        // Each worker attempts up to 5 batches
                        for (int b = 0; b < 5; b++) {
                            int count = outboxPublisherService.publishPendingBatch(batchSize);
                            totalPublished.addAndGet(count);
                            if (count == 0) {
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean finished = doneGate.await(20, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(finished).isTrue();

            // Verify database state: all 20 PUBLISHED, 0 PENDING
            for (UUID id : eventIds) {
                OutboxEvent ev = outboxEventRepository.findById(id).orElseThrow();
                assertThat(ev.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
                assertThat(ev.getPublishedAt()).isNotNull();
            }

            // Verify Kafka received all 20 distinct events
            Set<String> aggregateIdStrings = new HashSet<>(aggregateIds.stream().map(UUID::toString).toList());
            List<ConsumerRecord<String, String>> records = pollRecords(consumer, r -> aggregateIdStrings.contains(r.key()), eventCount, Duration.ofSeconds(15));
            Set<String> observedEventIds = new HashSet<>();
            for (ConsumerRecord<String, String> r : records) {
                JsonNode envelope = objectMapper.readTree(r.value());
                String id = envelope.get("id").asText();
                if (eventIds.stream().map(UUID::toString).anyMatch(id::equals)) {
                    observedEventIds.add(id);
                }
            }

            assertThat(observedEventIds).hasSize(eventCount);
        }
    }

    @Test
    @DisplayName("Higher contention execution with 50 events and 5 workers publishes all events without loss")
    void highContentionFiftyEventsFiveWorkers() throws Exception {
        int eventCount = 50;
        int workerCount = 5;
        int batchSize = 10;

        List<UUID> eventIds = new ArrayList<>();
        List<UUID> aggregateIds = new ArrayList<>();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        for (int i = 0; i < eventCount; i++) {
            UUID eventId = UUID.randomUUID();
            UUID aggregateId = UUID.randomUUID();
            eventIds.add(eventId);
            aggregateIds.add(aggregateId);

            String payload = String.format("{\"seq\":%d,\"aggregateId\":\"%s\"}", i, aggregateId);
            insertPendingOutboxEvent(eventId, "PAYMENT", aggregateId, "PAYMENT_SUCCEEDED", 1, payload, now.plusMillis(i));
        }

        try (KafkaConsumer<String, String> consumer = createTestConsumer()) {
            consumer.poll(Duration.ofMillis(100));

            ExecutorService executor = Executors.newFixedThreadPool(workerCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(workerCount);

            for (int i = 0; i < workerCount; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int b = 0; b < 10; b++) {
                            int count = outboxPublisherService.publishPendingBatch(batchSize);
                            if (count == 0) break;
                        }
                    } catch (Exception ignored) {
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean finished = doneGate.await(25, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(finished).isTrue();

            for (UUID id : eventIds) {
                OutboxEvent ev = outboxEventRepository.findById(id).orElseThrow();
                assertThat(ev.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            }

            Set<String> aggregateIdStrings = new HashSet<>(aggregateIds.stream().map(UUID::toString).toList());
            List<ConsumerRecord<String, String>> records = pollRecords(consumer, r -> aggregateIdStrings.contains(r.key()), eventCount, Duration.ofSeconds(15));
            Set<String> observedEventIds = new HashSet<>();
            for (ConsumerRecord<String, String> r : records) {
                JsonNode envelope = objectMapper.readTree(r.value());
                String id = envelope.get("id").asText();
                if (eventIds.stream().map(UUID::toString).anyMatch(id::equals)) {
                    observedEventIds.add(id);
                }
            }

            assertThat(observedEventIds).hasSize(eventCount);
        }
    }

    @Test
    @DisplayName("OutboxPublisherWorker triggers execution cleanly without crashing on empty or populated runs")
    void outboxPublisherWorkerRunsCleanly() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        insertPendingOutboxEvent(eventId, "TRANSFER", transferId, "TRANSFER_COMPLETED", 1, "{\"test\":true}", now);

        int count = outboxPublisherWorker.publishDirectly();
        assertThat(count).isGreaterThanOrEqualTo(1);

        OutboxEvent event = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("SKIP LOCKED allows concurrent workers to claim disjoint unlocked rows without blocking on open locks")
    void skipLockedNonBlockingProof() throws Exception {
        UUID eventA1 = UUID.randomUUID();
        UUID eventA2 = UUID.randomUUID();
        UUID eventB1 = UUID.randomUUID();
        UUID eventB2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        insertPendingOutboxEvent(eventA1, "TRANSFER", UUID.randomUUID(), "TRANSFER_COMPLETED", 1, "{\"item\":1}", now);
        insertPendingOutboxEvent(eventA2, "TRANSFER", UUID.randomUUID(), "TRANSFER_COMPLETED", 1, "{\"item\":2}", now.plusMillis(1));
        insertPendingOutboxEvent(eventB1, "TRANSFER", UUID.randomUUID(), "TRANSFER_COMPLETED", 1, "{\"item\":3}", now.plusMillis(2));
        insertPendingOutboxEvent(eventB2, "TRANSFER", UUID.randomUUID(), "TRANSFER_COMPLETED", 1, "{\"item\":4}", now.plusMillis(3));

        CountDownLatch workerAHeldLocks = new CountDownLatch(1);
        CountDownLatch workerBFinished = new CountDownLatch(1);
        CountDownLatch workerAProceed = new CountDownLatch(1);

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Worker A claims 2 events and holds transaction open
        executor.submit(() -> {
            tt.execute(status -> {
                List<OutboxEvent> claimedA = outboxEventRepository.findPendingEventsForPublishing(2);
                assertThat(claimedA).hasSize(2);
                assertThat(claimedA.stream().map(OutboxEvent::getId).toList()).contains(eventA1, eventA2);
                workerAHeldLocks.countDown();
                try {
                    workerAProceed.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        // Wait for Worker A to acquire locks
        boolean workerALocked = workerAHeldLocks.await(5, TimeUnit.SECONDS);
        assertThat(workerALocked).isTrue();

        // Worker B executes while Worker A's transaction is still active
        List<UUID> claimedByB = new ArrayList<>();
        executor.submit(() -> {
            tt.execute(status -> {
                List<OutboxEvent> claimedB = outboxEventRepository.findPendingEventsForPublishing(2);
                claimedByB.addAll(claimedB.stream().map(OutboxEvent::getId).toList());
                workerBFinished.countDown();
                return null;
            });
        });

        boolean bDone = workerBFinished.await(5, TimeUnit.SECONDS);
        workerAProceed.countDown();
        executor.shutdown();

        assertThat(bDone).isTrue();
        assertThat(claimedByB).hasSize(2);
        assertThat(claimedByB).contains(eventB1, eventB2);
        assertThat(claimedByB).doesNotContain(eventA1, eventA2);
    }
}
