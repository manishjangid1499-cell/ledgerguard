package com.ledgerguard.notification.application;

import com.ledgerguard.notification.AbstractNotificationWorkerIntegrationTest;
import com.ledgerguard.notification.domain.IncomingDomainEvent;
import com.ledgerguard.notification.domain.NotificationDelivery;
import com.ledgerguard.notification.domain.ProcessedEvent;
import com.ledgerguard.notification.domain.ProcessingOutcome;
import com.ledgerguard.notification.infrastructure.NotificationDeliveryRepository;
import com.ledgerguard.notification.infrastructure.ProcessedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationProcessingServiceIntegrationTest extends AbstractNotificationWorkerIntegrationTest {

    @Autowired
    private NotificationProcessingService processingService;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("First event processing atomically claims event and persists durable notification delivery")
    void firstEventProcessingPersistsClaimAndDelivery() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        IncomingDomainEvent event = new IncomingDomainEvent(
                eventId,
                "TRANSFER_COMPLETED",
                1,
                "TRANSFER",
                aggregateId,
                Instant.now(),
                objectMapper.createObjectNode()
        );

        ProcessingOutcome outcome = processingService.processEvent(event);
        assertThat(outcome).isEqualTo(ProcessingOutcome.PROCESSED);

        ProcessedEvent processedEvent = processedEventRepository.findById(eventId).orElseThrow();
        assertThat(processedEvent.getEventType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(processedEvent.getAggregateId()).isEqualTo(aggregateId);

        NotificationDelivery delivery = deliveryRepository.findByEventId(eventId).orElseThrow();
        assertThat(delivery.getEventType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(delivery.getAggregateId()).isEqualTo(aggregateId);
        assertThat(delivery.getStatus()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("Sequential duplicate execution returns DUPLICATE_SKIPPED and creates no additional delivery")
    void sequentialDuplicateSkipped() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        IncomingDomainEvent event = new IncomingDomainEvent(
                eventId,
                "PAYMENT_SUCCEEDED",
                1,
                "PAYMENT",
                aggregateId,
                Instant.now(),
                objectMapper.createObjectNode()
        );

        ProcessingOutcome outcome1 = processingService.processEvent(event);
        assertThat(outcome1).isEqualTo(ProcessingOutcome.PROCESSED);

        ProcessingOutcome outcome2 = processingService.processEvent(event);
        assertThat(outcome2).isEqualTo(ProcessingOutcome.DUPLICATE_SKIPPED);

        assertThat(processedEventRepository.findById(eventId)).isPresent();
        assertThat(deliveryRepository.findByAggregateId(aggregateId)).hasSize(1);
    }

    @Test
    @DisplayName("20 concurrent duplicate processing calls produce exactly 1 PROCESSED outcome and 1 delivery")
    void concurrentDuplicateCallsProduceExactlyOneDelivery() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        IncomingDomainEvent event = new IncomingDomainEvent(
                eventId,
                "REFUND_COMPLETED",
                1,
                "REFUND",
                aggregateId,
                Instant.now(),
                objectMapper.createObjectNode()
        );

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);

        List<ProcessingOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    ProcessingOutcome outcome = processingService.processEvent(event);
                    outcomes.add(outcome);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(outcomes).hasSize(threads);

        long processedCount = outcomes.stream().filter(o -> o == ProcessingOutcome.PROCESSED).count();
        long duplicateCount = outcomes.stream().filter(o -> o == ProcessingOutcome.DUPLICATE_SKIPPED).count();

        assertThat(processedCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(19);

        assertThat(processedEventRepository.findById(eventId)).isPresent();
        assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
    }

    @Test
    @DisplayName("Same aggregate ID with two different event IDs processes both independently")
    void sameAggregateDifferentEventIdsProcessBoth() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();

        IncomingDomainEvent event1 = new IncomingDomainEvent(
                eventId1,
                "TRANSFER_COMPLETED",
                1,
                "TRANSFER",
                aggregateId,
                Instant.now(),
                objectMapper.createObjectNode()
        );
        IncomingDomainEvent event2 = new IncomingDomainEvent(
                eventId2,
                "TRANSFER_COMPLETED",
                1,
                "TRANSFER",
                aggregateId,
                Instant.now().plusMillis(10),
                objectMapper.createObjectNode()
        );

        ProcessingOutcome outcome1 = processingService.processEvent(event1);
        ProcessingOutcome outcome2 = processingService.processEvent(event2);

        assertThat(outcome1).isEqualTo(ProcessingOutcome.PROCESSED);
        assertThat(outcome2).isEqualTo(ProcessingOutcome.PROCESSED);

        assertThat(processedEventRepository.findById(eventId1)).isPresent();
        assertThat(processedEventRepository.findById(eventId2)).isPresent();
        assertThat(deliveryRepository.findByAggregateId(aggregateId)).hasSize(2);
    }

    @Test
    @DisplayName("Delivery creation failure rolls back processed_events claim, enabling subsequent retry")
    void deliveryFailureRollsBackClaim() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        IncomingDomainEvent event = new IncomingDomainEvent(
                eventId,
                "TRANSFER_COMPLETED",
                1,
                "TRANSFER",
                aggregateId,
                Instant.now(),
                objectMapper.createObjectNode()
        );

        // Subclass / wrapper service that simulates failure during delivery creation
        NotificationProcessingService failingService = new NotificationProcessingService(
                processedEventRepository,
                new NotificationDeliveryService(deliveryRepository) {
                    @Override
                    public NotificationDelivery recordDelivery(IncomingDomainEvent ev) {
                        throw new RuntimeException("Simulated transient database/persistence failure during delivery creation");
                    }
                }
        );

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            tt.execute(status -> failingService.processEvent(event));
        } catch (RuntimeException ignored) {
        }

        // Verify that because of rollback, processed_events does NOT contain the event
        assertThat(processedEventRepository.findById(eventId)).isEmpty();
        assertThat(deliveryRepository.findByEventId(eventId)).isEmpty();

        // Subsequent retry with the real service succeeds
        ProcessingOutcome retryOutcome = processingService.processEvent(event);
        assertThat(retryOutcome).isEqualTo(ProcessingOutcome.PROCESSED);

        assertThat(processedEventRepository.findById(eventId)).isPresent();
        assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
    }
}
