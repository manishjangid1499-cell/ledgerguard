package com.ledgerguard;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.security.SecureRandom;
import java.util.Base64;

@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    public static final PostgreSQLContainer<?> POSTGRES_CONTAINER =
            new PostgreSQLContainer<>("postgres:17.11-alpine")
                    .withDatabaseName("ledgerguard_test")
                    .withUsername("test_user")
                    .withPassword("test_pass");

    public static final KafkaContainer KAFKA_CONTAINER =
            new KafkaContainer("apache/kafka:4.3.1");

    private static final String RUNTIME_JWT_SECRET;
    public static final String RUNTIME_WEBHOOK_SECRET;

    static {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        RUNTIME_JWT_SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        byte[] webhookBytes = new byte[32];
        new SecureRandom().nextBytes(webhookBytes);
        RUNTIME_WEBHOOK_SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(webhookBytes);

        POSTGRES_CONTAINER.start();
        KAFKA_CONTAINER.start();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        registry.add("ledgerguard.security.jwt.secret", () -> RUNTIME_JWT_SECRET);
        registry.add("ledgerguard.psp.webhook.secret", () -> RUNTIME_WEBHOOK_SECRET);
    }
}
