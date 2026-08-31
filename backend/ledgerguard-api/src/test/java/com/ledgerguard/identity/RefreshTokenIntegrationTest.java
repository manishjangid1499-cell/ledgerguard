package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.api.AuthController;
import com.ledgerguard.identity.application.RefreshTokenService;
import com.ledgerguard.identity.domain.RefreshToken;
import com.ledgerguard.identity.domain.RefreshTokenRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.shared.error.ApiErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshTokenIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Valid refresh token rotation issues new access token, sets new cookie, and revokes old token")
    void refreshTokenRotationSucceeds() throws Exception {
        User user = new User(UUID.randomUUID(), "refresh.user@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        RefreshTokenService.GeneratedToken initialToken = refreshTokenService.createRefreshToken(user);
        String oldRawToken = initialToken.rawToken();

        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, oldRawToken)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(cookie().exists(AuthController.REFRESH_COOKIE_NAME))
                .andReturn();

        Cookie newCookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
        assertThat(newCookie).isNotNull();
        assertThat(newCookie.getValue()).isNotEqualTo(oldRawToken);

        // Verify the old token is now marked as revoked in database
        String oldHash = refreshTokenService.hashToken(oldRawToken);
        RefreshToken oldEntity = refreshTokenRepository.findByTokenHash(oldHash).orElseThrow();
        assertThat(oldEntity.isRevoked()).isTrue();
        assertThat(oldEntity.getRevokedAt()).isNotNull();

        // Verify attempting to reuse the old refresh token fails with 401
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, oldRawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_REFRESH_TOKEN)));
    }

    @Test
    @DisplayName("User disabled after login cannot refresh token to obtain a new access token")
    void disabledUserCannotRefreshToken() throws Exception {
        User user = new User(UUID.randomUUID(), "disable.refresh@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        RefreshTokenService.GeneratedToken initialToken = refreshTokenService.createRefreshToken(user);
        String rawToken = initialToken.rawToken();

        // Admin/system changes user to DISABLED
        user.disable();
        userRepository.save(user);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_REFRESH_TOKEN)));
    }

    @Test
    @DisplayName("Expired refresh token is rejected with 401 Unauthorized")
    void expiredRefreshTokenRejected() throws Exception {
        User user = new User(UUID.randomUUID(), "expired.user@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String rawToken = "raw-expired-token-value-example-1234567";
        String tokenHash = refreshTokenService.hashToken(rawToken);
        RefreshToken expiredEntity = new RefreshToken(UUID.randomUUID(), user, tokenHash, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(10));
        refreshTokenRepository.save(expiredEntity);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_REFRESH_TOKEN)));
    }

    @Test
    @DisplayName("Logout revokes active refresh token and clears cookie idempotently")
    void logoutRevokesTokenAndClearsCookie() throws Exception {
        User user = new User(UUID.randomUUID(), "logout.user@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        RefreshTokenService.GeneratedToken generatedToken = refreshTokenService.createRefreshToken(user);
        String rawToken = generatedToken.rawToken();

        // First logout call
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rawToken)))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthController.REFRESH_COOKIE_NAME, 0));

        // Verify token is revoked in DB
        String hash = refreshTokenService.hashToken(rawToken);
        RefreshToken entity = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
        assertThat(entity.isRevoked()).isTrue();

        // Second logout call is idempotent (no error, 204 No Content)
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rawToken)))
                .andExpect(status().isNoContent());

        // Refresh with logged out token fails
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_REFRESH_TOKEN)));
    }
}
