package com.ledgerguard.psp;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractPspSimulatorIntegrationTest {

    public static final String RUNTIME_WEBHOOK_SECRET;
    protected static final PostgreSQLContainer<?> POSTGRES_CONTAINER;

    static {
        byte[] webhookBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(webhookBytes);
        RUNTIME_WEBHOOK_SECRET = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(webhookBytes);

        POSTGRES_CONTAINER = new PostgreSQLContainer<>("postgres:17.11-alpine")
                .withDatabaseName("psp_simulator_test");
        POSTGRES_CONTAINER.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);
        registry.add("ledgerguard.psp.webhook.secret", () -> RUNTIME_WEBHOOK_SECRET);
    }
}
