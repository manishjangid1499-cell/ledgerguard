package com.ledgerguard.notification.infrastructure;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DeadLetterPublishingRecovererReliabilityTest {

    @Test
    @DisplayName("DeadLetterPublishingRecoverer throws exception when DLT send fails, preventing silent recovery")
    void dltSendFailureThrowsException() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> mockKafkaTemplate = Mockito.mock(KafkaTemplate.class);

        // Simulate failed DLT send future
        CompletableFuture<?> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Simulated DLT broker network failure"));
        when(mockKafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn((CompletableFuture) failedFuture);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                mockKafkaTemplate,
                (record, exception) -> new TopicPartition("ledgerguard.domain-events.v1.DLT", record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(5));

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "ledgerguard.domain-events.v1",
                0,
                100L,
                "aggregate-123",
                "{\"invalid\": \"payload\"}"
        );
        RuntimeException listenerException = new RuntimeException("Validation failed");

        // Verify that DeadLetterPublishingRecoverer propagates failure instead of silently swallowing it
        assertThatThrownBy(() -> recoverer.accept(record, listenerException))
                .isNotNull();
    }
}
