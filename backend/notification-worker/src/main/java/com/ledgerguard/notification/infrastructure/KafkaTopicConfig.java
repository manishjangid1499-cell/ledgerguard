package com.ledgerguard.notification.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic domainEventsDltTopic(
            @Value("${ledgerguard.kafka.domain-events-dlt-topic:ledgerguard.domain-events.v1.DLT}") String dltTopicName
    ) {
        return TopicBuilder.name(dltTopicName)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
