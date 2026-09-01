package com.ledgerguard.outbox.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic provisioning configuration for domain events.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic domainEventsTopic(
            @Value("${ledgerguard.kafka.domain-events-topic:ledgerguard.domain-events.v1}") String topicName,
            @Value("${ledgerguard.kafka.partitions:3}") int partitions,
            @Value("${ledgerguard.kafka.replication-factor:1}") int replicationFactor
    ) {
        return TopicBuilder.name(topicName)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }
}
