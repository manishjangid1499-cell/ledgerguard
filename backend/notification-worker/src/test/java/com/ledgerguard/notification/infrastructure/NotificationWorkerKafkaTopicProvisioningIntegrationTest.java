package com.ledgerguard.notification.infrastructure;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "ledgerguard.notification.email.enabled=false"
})
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Notification Worker Kafka startup DLT topic provisioning regression test")
class NotificationWorkerKafkaTopicProvisioningIntegrationTest {

    private static final String UNIQUE_DLT_TOPIC = "ledgerguard.domain-events.worker-startup-test-" + UUID.randomUUID() + ".DLT";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine")
            .withDatabaseName("nw_kafka_provisioning_test");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("ledgerguard.kafka.domain-events-dlt-topic", () -> UNIQUE_DLT_TOPIC);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private NewTopic domainEventsDltTopic;

    @Value("${ledgerguard.kafka.domain-events-dlt-topic}")
    private String configuredDltTopicName;

    @Test
    @DisplayName("Assert KafkaAdmin exists, production NewTopic bean matches, and DLT topic is provisioned with 3 partitions and replication factor 1")
    void workerStartupProvisionsDltTopicWithoutAutoCreate() throws Exception {
        // 1. Assert a KafkaAdmin bean exists in ApplicationContext
        assertThat(applicationContext.containsBean("kafkaAdmin")).isTrue();
        KafkaAdmin kafkaAdmin = applicationContext.getBean(KafkaAdmin.class);
        assertThat(kafkaAdmin).isNotNull();

        // 2. Production NewTopic bean verification
        assertThat(domainEventsDltTopic).isNotNull();
        assertThat(configuredDltTopicName).isEqualTo(UNIQUE_DLT_TOPIC);
        assertThat(domainEventsDltTopic.name()).isEqualTo(UNIQUE_DLT_TOPIC);
        assertThat(domainEventsDltTopic.numPartitions()).isEqualTo(3);
        assertThat(domainEventsDltTopic.replicationFactor()).isEqualTo((short) 1);

        // 3. Inspect Kafka broker directly using bounded AdminClient to verify actual provisioning
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Map<String, TopicDescription> topics = adminClient.describeTopics(Collections.singletonList(UNIQUE_DLT_TOPIC))
                    .allTopicNames()
                    .get(5, TimeUnit.SECONDS);

            assertThat(topics).containsKey(UNIQUE_DLT_TOPIC);
            TopicDescription topicDesc = topics.get(UNIQUE_DLT_TOPIC);
            assertThat(topicDesc).isNotNull();
            assertThat(topicDesc.name()).isEqualTo(UNIQUE_DLT_TOPIC);
            assertThat(topicDesc.partitions()).hasSize(3);
            topicDesc.partitions().forEach(partition -> {
                assertThat(partition.replicas()).hasSize(1);
            });
        }
    }
}