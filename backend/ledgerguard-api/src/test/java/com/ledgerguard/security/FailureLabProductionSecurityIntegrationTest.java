package com.ledgerguard.security;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("FailureLabProductionSecurityIntegrationTest — Production isolation verification")
@ActiveProfiles("prod")
class FailureLabProductionSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private com.ledgerguard.identity.domain.UserRepository userRepository;

    @Autowired
    private com.ledgerguard.shared.security.JwtTokenService jwtTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Unauthenticated requests to Failure Lab endpoints receive 401 Unauthorized")
    void unauthenticatedLabEndpointsReturn401() throws Exception {
        mockMvc.perform(get("/api/lab/environment"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated requests to Failure Lab endpoints receive 404 Not Found (unmapped in production API)")
    void authenticatedLabEndpointsReturn404() throws Exception {
        com.ledgerguard.identity.domain.User opsUser = userRepository.save(
                new com.ledgerguard.identity.domain.User(
                        java.util.UUID.randomUUID(),
                        "ops.prod." + java.util.UUID.randomUUID() + "@example.com",
                        "$2a$10$hash",
                        com.ledgerguard.identity.domain.UserRole.OPS,
                        com.ledgerguard.identity.domain.UserStatus.ACTIVE
                )
        );
        String opsToken = jwtTokenService.generateAccessToken(opsUser);

        mockMvc.perform(get("/api/lab/environment")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/lab/scenarios")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/lab/runs")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/lab/runs/active")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Failure Lab engine and scenario beans are absent from the production application context")
    void failureLabBeansAbsentFromContext() {
        String[] allBeanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : allBeanNames) {
            Object bean = applicationContext.getBean(beanName);
            String className = bean.getClass().getName();
            assertThat(className)
                    .as("Bean '%s' class '%s' must not be in com.ledgerguard.lab package", beanName, className)
                    .doesNotContain("com.ledgerguard.lab");
        }
    }
}
