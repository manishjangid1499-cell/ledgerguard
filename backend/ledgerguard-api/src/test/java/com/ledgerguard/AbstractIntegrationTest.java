package com.ledgerguard;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

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

    private static final String RUNTIME_JWT_SECRET;

    static {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        RUNTIME_JWT_SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        POSTGRES_CONTAINER.start();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES_CONTAINER::getUsername);
        registry.add("spring.datasource.password", POSTGRES_CONTAINER::getPassword);
        registry.add("ledgerguard.security.jwt.secret", () -> RUNTIME_JWT_SECRET);
    }
}
