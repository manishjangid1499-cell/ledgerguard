package com.ledgerguard.outbox.infrastructure;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.NewTopic;
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

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("API Kafka startup topic provisioning regression test")
class ApiKafkaTopicProvisioningIntegrationTest {

    private static final String UNIQUE_DOMAIN_EVENTS_TOPIC = "ledgerguard.domain-events.api-startup-test-" + UUID.randomUUID();
    private static final String RUNTIME_JWT_SECRET;
    private static final String RUNTIME_WEBHOOK_SECRET;

    static {
        byte[] jwtBytes = new byte[32];
        new SecureRandom().nextBytes(jwtBytes);
        RUNTIME_JWT_SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(jwtBytes);

        byte[] webhookBytes = new byte[32];
        new SecureRandom().nextBytes(webhookBytes);
        RUNTIME_WEBHOOK_SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(webhookBytes);
    }

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lg_api_kafka_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withCommand("postgres", "-c", "max_connections=300");

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("ledgerguard.kafka.domain-events-topic", () -> UNIQUE_DOMAIN_EVENTS_TOPIC);
        registry.add("ledgerguard.security.jwt.secret", () -> RUNTIME_JWT_SECRET);
        registry.add("ledgerguard.psp.webhook.secret", () -> RUNTIME_WEBHOOK_SECRET);
        registry.add("ledgerguard.psp.polling.enabled", () -> "false");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private NewTopic domainEventsTopic;

    @Value("${ledgerguard.kafka.domain-events-topic}")
    private String configuredTopicName;

    @Test
    @DisplayName("Assert KafkaAdmin exists, production NewTopic bean matches, and topic is provisioned with 3 partitions and replication factor 1")
    void apiStartupProvisionsDomainEventsTopicWithoutAutoCreate() throws Exception {
        // 1. Assert a KafkaAdmin bean exists in ApplicationContext
        assertThat(applicationContext.containsBean("kafkaAdmin")).isTrue();
        KafkaAdmin kafkaAdmin = applicationContext.getBean(KafkaAdmin.class);
        assertThat(kafkaAdmin).isNotNull();

        // 2. Production NewTopic bean verification
        assertThat(domainEventsTopic).isNotNull();
        assertThat(configuredTopicName).isEqualTo(UNIQUE_DOMAIN_EVENTS_TOPIC);
        assertThat(domainEventsTopic.name()).isEqualTo(UNIQUE_DOMAIN_EVENTS_TOPIC);
        assertThat(domainEventsTopic.numPartitions()).isEqualTo(3);
        assertThat(domainEventsTopic.replicationFactor()).isEqualTo((short) 1);

        // 3. Inspect Kafka broker directly using bounded AdminClient to verify actual provisioning
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Map<String, TopicDescription> topics = adminClient.describeTopics(Collections.singletonList(UNIQUE_DOMAIN_EVENTS_TOPIC))
                    .allTopicNames()
                    .get(5, TimeUnit.SECONDS);

            assertThat(topics).containsKey(UNIQUE_DOMAIN_EVENTS_TOPIC);
            TopicDescription topicDesc = topics.get(UNIQUE_DOMAIN_EVENTS_TOPIC);
            assertThat(topicDesc).isNotNull();
            assertThat(topicDesc.name()).isEqualTo(UNIQUE_DOMAIN_EVENTS_TOPIC);
            assertThat(topicDesc.partitions()).hasSize(3);
            topicDesc.partitions().forEach(partition -> {
                assertThat(partition.replicas()).hasSize(1);
            });
        }
    }
}