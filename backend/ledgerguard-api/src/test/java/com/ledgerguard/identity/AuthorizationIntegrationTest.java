package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.shared.error.ApiErrorCode;
import com.ledgerguard.shared.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthorizationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("GET /api/auth/me with valid Bearer token returns authenticated user summary")
    void getCurrentUserSucceedsWithValidToken() throws Exception {
        User user = new User(UUID.randomUUID(), "authme.user@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);
        String token = jwtTokenService.generateAccessToken(user);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", is(user.getId().toString())))
                .andExpect(jsonPath("$.email", is("authme.user@example.com")))
                .andExpect(jsonPath("$.role", is("CUSTOMER")));
    }

    @Test
    @DisplayName("GET /api/auth/me without token returns 401 Unauthorized with AUTHENTICATION_REQUIRED")
    void getCurrentUserFailsWithoutToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.AUTHENTICATION_REQUIRED)));
    }

    @Test
    @DisplayName("GET /api/auth/me with malformed bearer token returns 401 Unauthorized")
    void getCurrentUserFailsWithMalformedToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer not.a.valid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.AUTHENTICATION_REQUIRED)));
    }

    @Test
    @DisplayName("OPS role can access /api/ops/dashboard")
    void opsRoleCanAccessOpsEndpoints() throws Exception {
        User opsUser = new User(UUID.randomUUID(), "ops.user@example.com", "$2a$hash", UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(opsUser);
        String opsToken = jwtTokenService.generateAccessToken(opsUser);

        mockMvc.perform(get("/api/ops/dashboard")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("OPS_OK")));
    }

    @Test
    @DisplayName("CUSTOMER role accessing /api/ops/dashboard returns 403 Forbidden with ACCESS_DENIED")
    void customerRoleCannotAccessOpsEndpoints() throws Exception {
        User customerUser = new User(UUID.randomUUID(), "regular.customer@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customerUser);
        String customerToken = jwtTokenService.generateAccessToken(customerUser);

        mockMvc.perform(get("/api/ops/dashboard")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.ACCESS_DENIED)))
                .andExpect(jsonPath("$.title", is("Forbidden")));
    }

    @Test
    @DisplayName("MERCHANT role accessing /api/ops/dashboard returns 403 Forbidden with ACCESS_DENIED")
    void merchantRoleCannotAccessOpsEndpoints() throws Exception {
        User merchantUser = new User(UUID.randomUUID(), "regular.merchant@example.com", "$2a$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(merchantUser);
        String merchantToken = jwtTokenService.generateAccessToken(merchantUser);

        mockMvc.perform(get("/api/ops/dashboard")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.ACCESS_DENIED)));
    }
}
