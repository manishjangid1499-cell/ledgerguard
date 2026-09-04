package com.ledgerguard.notification;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationWorkerApplicationTests extends AbstractNotificationWorkerIntegrationTest {

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    @DisplayName("Bounded Kafka consumer backpressure properties are enforced")
    void boundedConsumerPropertiesEnforced() {
        assertThat(consumerFactory.getConfigurationProperties().get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(10);
        assertThat(environment.getProperty("spring.kafka.consumer.max-poll-records", Integer.class)).isEqualTo(10);
        assertThat(environment.getProperty("spring.kafka.listener.concurrency", Integer.class)).isEqualTo(3);
    }
}
