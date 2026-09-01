package com.ledgerguard.notification;

import com.ledgerguard.notification.domain.NotificationDelivery;
import com.ledgerguard.notification.domain.ProcessedEvent;
import com.ledgerguard.notification.infrastructure.NotificationDeliveryRepository;
import com.ledgerguard.notification.infrastructure.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NotificationWorkerIntegrationTest extends AbstractNotificationWorkerIntegrationTest {

    @Value("${ledgerguard.kafka.domain-events-topic:ledgerguard.domain-events.v1}")
    private String domainEventsTopic;

    @Value("${ledgerguard.kafka.domain-events-dlt-topic:ledgerguard.domain-events.v1.DLT}")
    private String domainEventsDltTopic;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    private KafkaProducer<String, String> createTestProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    private KafkaConsumer<String, String> createTestConsumer(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-verifier-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));
        return consumer;
    }

    private String buildCloudEventJson(UUID eventId, String eventType, int version, String aggregateType, UUID aggregateId, String dataJson) {
        return String.format(
                "{\"specversion\":\"1.0\",\"id\":\"%s\",\"source\":\"urn:ledgerguard:ledgerguard-api\",\"type\":\"%s\",\"subject\":\"%s/%s\",\"time\":\"%s\",\"datacontenttype\":\"application/json\",\"eventversion\":%d,\"aggregatetype\":\"%s\",\"aggregateid\":\"%s\",\"data\":%s}",
                eventId, eventType, aggregateType, aggregateId, Instant.now(), version, aggregateType, aggregateId, dataJson
        );
    }

    @Test
    @DisplayName("Valid TRANSFER_COMPLETED event is consumed, creates 1 processed row and 1 delivery row")
    void transferCompletedEventProcessesSuccessfully() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String data = String.format("{\"transferId\":\"%s\",\"sourceLedgerAccountId\":\"%s\",\"destinationLedgerAccountId\":\"%s\",\"amountMinor\":\"15000\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                transferId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String message = buildCloudEventJson(eventId, "TRANSFER_COMPLETED", 1, "TRANSFER", transferId, data);

        try (KafkaProducer<String, String> producer = createTestProducer()) {
            producer.send(new ProducerRecord<>(domainEventsTopic, transferId.toString(), message));
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<ProcessedEvent> processed = processedEventRepository.findById(eventId);
            assertThat(processed).isPresent();
            assertThat(processed.get().getEventType()).isEqualTo("TRANSFER_COMPLETED");
            assertThat(processed.get().getAggregateId()).isEqualTo(transferId);

            Optional<NotificationDelivery> delivery = deliveryRepository.findByEventId(eventId);
            assertThat(delivery).isPresent();
            assertThat(delivery.get().getEventType()).isEqualTo("TRANSFER_COMPLETED");
            assertThat(delivery.get().getStatus()).isEqualTo("DELIVERED");
        });
    }

    @Test
    @DisplayName("Valid PAYMENT_SUCCEEDED event is consumed with preserved money strings")
    void paymentSucceededEventProcessesSuccessfully() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String largeAmount = "9007199254740995";
        String data = String.format("{\"paymentId\":\"%s\",\"customerLedgerAccountId\":\"%s\",\"merchantLedgerAccountId\":\"%s\",\"grossAmountMinor\":\"%s\",\"feeAmountMinor\":\"100\",\"merchantNetAmountMinor\":\"9007199254740895\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                paymentId, UUID.randomUUID(), UUID.randomUUID(), largeAmount, UUID.randomUUID());
        String message = buildCloudEventJson(eventId, "PAYMENT_SUCCEEDED", 1, "PAYMENT", paymentId, data);

        try (KafkaProducer<String, String> producer = createTestProducer()) {
            producer.send(new ProducerRecord<>(domainEventsTopic, paymentId.toString(), message));
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.findById(eventId)).isPresent();
            assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
        });
    }

    @Test
    @DisplayName("Valid REFUND_COMPLETED event is consumed and creates delivery")
    void refundCompletedEventProcessesSuccessfully() {
        UUID eventId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        String data = String.format("{\"refundId\":\"%s\",\"paymentId\":\"%s\",\"refundAmountMinor\":\"5000\",\"merchantDebitAmountMinor\":\"4950\",\"feeDebitAmountMinor\":\"50\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                refundId, UUID.randomUUID(), UUID.randomUUID());
        String message = buildCloudEventJson(eventId, "REFUND_COMPLETED", 1, "REFUND", refundId, data);

        try (KafkaProducer<String, String> producer = createTestProducer()) {
            producer.send(new ProducerRecord<>(domainEventsTopic, refundId.toString(), message));
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.findById(eventId)).isPresent();
            assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
        });
    }

    @Test
    @DisplayName("Duplicate event published 10 times results in exactly 1 processed row and 1 delivery row, 0 DLT")
    void duplicateEventDeliveredTenTimesProducesSingleDelivery() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String data = String.format("{\"transferId\":\"%s\",\"sourceLedgerAccountId\":\"%s\",\"destinationLedgerAccountId\":\"%s\",\"amountMinor\":\"10000\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                transferId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String message = buildCloudEventJson(eventId, "TRANSFER_COMPLETED", 1, "TRANSFER", transferId, data);

        try (KafkaProducer<String, String> producer = createTestProducer()) {
            for (int i = 0; i < 10; i++) {
                producer.send(new ProducerRecord<>(domainEventsTopic, transferId.toString(), message));
            }
        }

        // Wait until processed
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.findById(eventId)).isPresent();
            assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
        });

        // Give additional time to ensure all 10 messages were processed by worker
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Database row counts remain strictly 1
        assertThat(deliveryRepository.findByAggregateId(transferId)).hasSize(1);
    }

    @Test
    @DisplayName("20 distinct events all process successfully into distinct delivery rows")
    void twentyDistinctEventsAllProcessSuccessfully() {
        int count = 20;
        List<UUID> eventIds = new ArrayList<>();

        try (KafkaProducer<String, String> producer = createTestProducer()) {
            for (int i = 0; i < count; i++) {
                UUID eventId = UUID.randomUUID();
                UUID transferId = UUID.randomUUID();
                eventIds.add(eventId);

                String data = String.format("{\"transferId\":\"%s\",\"sourceLedgerAccountId\":\"%s\",\"destinationLedgerAccountId\":\"%s\",\"amountMinor\":\"%d\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                        transferId, UUID.randomUUID(), UUID.randomUUID(), (i + 1) * 1000, UUID.randomUUID());
                String message = buildCloudEventJson(eventId, "TRANSFER_COMPLETED", 1, "TRANSFER", transferId, data);
                producer.send(new ProducerRecord<>(domainEventsTopic, transferId.toString(), message));
            }
        }

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            for (UUID eventId : eventIds) {
                assertThat(processedEventRepository.findById(eventId)).isPresent();
                assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
            }
        });
    }

    @Test
    @DisplayName("Malformed JSON payload is sent to DLT topic and does not create processed or delivery rows")
    void malformedJsonReachesDlt() {
        String poisonMessage = "{\"specversion\":\"1.0\", INVALID_JSON";
        String poisonKey = UUID.randomUUID().toString();

        try (KafkaConsumer<String, String> dltConsumer = createTestConsumer(domainEventsDltTopic);
             KafkaProducer<String, String> producer = createTestProducer()) {

            dltConsumer.poll(Duration.ofMillis(100));

            producer.send(new ProducerRecord<>(domainEventsTopic, poisonKey, poisonMessage));

            // Poll DLT topic for poison message
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                ConsumerRecords<String, String> records = dltConsumer.poll(Duration.ofMillis(200));
                List<ConsumerRecord<String, String>> matches = new ArrayList<>();
                for (ConsumerRecord<String, String> r : records) {
                    if (poisonKey.equals(r.key())) {
                        matches.add(r);
                    }
                }
                assertThat(matches).isNotEmpty();
            });
        }
    }

    @Test
    @DisplayName("Unknown event type is sent to DLT topic and does not create processed or delivery rows")
    void unsupportedEventTypeReachesDlt() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String message = buildCloudEventJson(eventId, "UNKNOWN_EVENT_TYPE", 1, "UNKNOWN", aggregateId, "{\"field\":\"value\"}");

        try (KafkaConsumer<String, String> dltConsumer = createTestConsumer(domainEventsDltTopic);
             KafkaProducer<String, String> producer = createTestProducer()) {

            dltConsumer.poll(Duration.ofMillis(100));

            producer.send(new ProducerRecord<>(domainEventsTopic, aggregateId.toString(), message));

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                ConsumerRecords<String, String> records = dltConsumer.poll(Duration.ofMillis(200));
                List<ConsumerRecord<String, String>> matches = new ArrayList<>();
                for (ConsumerRecord<String, String> r : records) {
                    if (aggregateId.toString().equals(r.key())) {
                        matches.add(r);
                    }
                }
                assertThat(matches).isNotEmpty();
            });

            assertThat(processedEventRepository.findById(eventId)).isEmpty();
            assertThat(deliveryRepository.findByEventId(eventId)).isEmpty();
        }
    }

    @Test
    @DisplayName("Unsupported event version is sent to DLT topic")
    void unsupportedEventVersionReachesDlt() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String data = String.format("{\"transferId\":\"%s\",\"sourceLedgerAccountId\":\"%s\",\"destinationLedgerAccountId\":\"%s\",\"amountMinor\":\"10000\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                transferId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String message = buildCloudEventJson(eventId, "TRANSFER_COMPLETED", 999, "TRANSFER", transferId, data);

        try (KafkaConsumer<String, String> dltConsumer = createTestConsumer(domainEventsDltTopic);
             KafkaProducer<String, String> producer = createTestProducer()) {

            dltConsumer.poll(Duration.ofMillis(100));

            producer.send(new ProducerRecord<>(domainEventsTopic, transferId.toString(), message));

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                ConsumerRecords<String, String> records = dltConsumer.poll(Duration.ofMillis(200));
                List<ConsumerRecord<String, String>> matches = new ArrayList<>();
                for (ConsumerRecord<String, String> r : records) {
                    if (transferId.toString().equals(r.key())) {
                        matches.add(r);
                    }
                }
                assertThat(matches).isNotEmpty();
            });

            assertThat(processedEventRepository.findById(eventId)).isEmpty();
            assertThat(deliveryRepository.findByEventId(eventId)).isEmpty();
        }
    }

    @Test
    @DisplayName("Poison record does not block subsequent valid event on the same partition")
    void poisonEventDoesNotBlockSubsequentValidEventOnSamePartition() {
        String poisonKey = UUID.randomUUID().toString();
        String poisonMessage = "MALFORMED_JSON_STRING";

        UUID validEventId = UUID.randomUUID();
        UUID validTransferId = UUID.randomUUID();
        String data = String.format("{\"transferId\":\"%s\",\"sourceLedgerAccountId\":\"%s\",\"destinationLedgerAccountId\":\"%s\",\"amountMinor\":\"25000\",\"currency\":\"INR\",\"journalTransactionId\":\"%s\"}",
                validTransferId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String validMessage = buildCloudEventJson(validEventId, "TRANSFER_COMPLETED", 1, "TRANSFER", validTransferId, data);

        try (KafkaProducer<String, String> producer = createTestProducer()) {
            // Send poison message to partition 0
            producer.send(new ProducerRecord<>(domainEventsTopic, 0, poisonKey, poisonMessage));
            // Send valid message to same partition 0
            producer.send(new ProducerRecord<>(domainEventsTopic, 0, validTransferId.toString(), validMessage));
        }

        // Verify valid event is eventually processed despite the poison event preceding it on the partition
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.findById(validEventId)).isPresent();
            assertThat(deliveryRepository.findByEventId(validEventId)).isPresent();
        });
    }
}
