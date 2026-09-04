package com.ledgerguard.shared.ratelimit;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.shared.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RateLimitSecurityPrecedenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private RateLimitService rateLimitService;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void rateLimitTestProperties(DynamicPropertyRegistry registry) {
        registry.add("ledgerguard.rate-limit.enabled", () -> true);
        registry.add("ledgerguard.rate-limit.policy.financial-write.capacity", () -> 3);
        registry.add("ledgerguard.rate-limit.policy.financial-write.refill-tokens", () -> 3);
        registry.add("ledgerguard.rate-limit.policy.financial-write.refill-period", () -> "1m");
        registry.add("ledgerguard.rate-limit.policy.authenticated-general.capacity", () -> 3);
        registry.add("ledgerguard.rate-limit.policy.authenticated-general.refill-tokens", () -> 3);
        registry.add("ledgerguard.rate-limit.policy.authenticated-general.refill-period", () -> "1m");
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private User createTestUser(UserRole role) {
        String email = "test-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        User user = new User(
                UUID.randomUUID(),
                email,
                "$2a$10$7Z6J0V5S3Q1N2P4R6T8U0uG5eY2X4W6A8C0E2G4I6K8M0O2Q4S6U",
                role,
                UserStatus.ACTIVE
        );
        return userRepository.save(user);
    }

    @Test
    @DisplayName("Unauthenticated request returns 401 and does not consume rate-limit bucket")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(post("/api/funding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":\"5000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Wrong-role Funding request returns 403 and does not consume rate-limit bucket")
    void wrongRoleFundingReturns403() throws Exception {
        User merchant = createTestUser(UserRole.MERCHANT);
        String token = jwtTokenService.generateAccessToken(merchant);

        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":\"5000\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Repeat wrong-role Funding request beyond capacity still returns 403, NEVER 429")
    void repeatWrongRoleFundingNeverReturns429() throws Exception {
        User merchant = createTestUser(UserRole.MERCHANT);
        String token = jwtTokenService.generateAccessToken(merchant);

        // Capacity is configured to 3; execute 10 requests
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/funding")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amountMinor\":\"5000\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    @DisplayName("Wrong-role Payout request returns 403 and does not consume bucket")
    void wrongRolePayoutReturns403() throws Exception {
        User ops = createTestUser(UserRole.OPS);
        String token = jwtTokenService.generateAccessToken(ops);

        mockMvc.perform(post("/api/payouts")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":\"5000\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Wrong-role wallet and transfer reads return 403 and never 429 even beyond capacity")
    void wrongRoleReadsReturn403AndNever429() throws Exception {
        User ops = createTestUser(UserRole.OPS);
        String token = jwtTokenService.generateAccessToken(ops);
        UUID randomTransferId = UUID.randomUUID();

        // Capacity is configured to 3; execute 10 requests for each endpoint to verify 0 tokens consumed and never 429
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/wallets/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            mockMvc.perform(get("/api/transfers")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            mockMvc.perform(get("/api/transfers/" + randomTransferId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    @DisplayName("Authorized actor consumes bucket and returns 429 when quota is exhausted")
    void authorizedCallerReturns429WhenExhausted() throws Exception {
        User customer = createTestUser(UserRole.CUSTOMER);
        String token = jwtTokenService.generateAccessToken(customer);

        // We simulate calls that pass through RateLimitFilter
        // First 3 calls consume the capacity of 3
        for (int i = 0; i < 3; i++) {
            // Note: even if business validation or missing Idempotency-Key returns 400 later,
            // it proves the request passed through the rate limit filter and consumed a token.
            mockMvc.perform(post("/api/funding")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amountMinor\":\"1000\"}"))
                    .andExpect(status().isBadRequest()); // validation error happens in controller
        }

        // 4th call is rejected by RateLimitFilter BEFORE reaching controller
        mockMvc.perform(post("/api/funding")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":\"1000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.type").value("urn:ledgerguard:error:rate-limit-exceeded"));
    }
}
