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
import com.ledgerguard.shared.security.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtProperties jwtProperties;

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
        assertThat(newCookie.getMaxAge()).isEqualTo(-1);

        String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("SameSite=Strict");
        assertThat(setCookieHeader).contains("Path=/api/auth");
        assertThat(setCookieHeader).doesNotContain("Max-Age");
        assertThat(setCookieHeader).doesNotContain("Expires");

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

    @Test
    @DisplayName("Concurrent refresh and logout: logout invalidates the session and successor token cannot be refreshed")
    void concurrentRefreshAndLogoutDoesNotLeaveUsableSuccessorToken() throws Exception {
        User user = new User(UUID.randomUUID(), "concur.logout@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        RefreshTokenService.GeneratedToken initialToken = refreshTokenService.createRefreshToken(user);
        String oldRawToken = initialToken.rawToken();

        int concurrency = 2;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(concurrency);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> rotatedTokenRef = new java.util.concurrent.atomic.AtomicReference<>();

        java.util.concurrent.Future<?> fRefresh = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                MvcResult res = mockMvc.perform(post("/api/auth/refresh")
                                .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, oldRawToken)))
                        .andReturn();
                if (res.getResponse().getStatus() == 200) {
                    Cookie cookie = res.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
                    if (cookie != null) {
                        rotatedTokenRef.set(cookie.getValue());
                    }
                }
            } catch (Exception e) {
                // Handled
            }
        });

        java.util.concurrent.Future<?> fLogout = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                mockMvc.perform(post("/api/auth/logout")
                                .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, oldRawToken)))
                        .andExpect(status().isNoContent());
            } catch (Exception e) {
                // Handled
            }
        });

        readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        startLatch.countDown();

        fRefresh.get(10, java.util.concurrent.TimeUnit.SECONDS);
        fLogout.get(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // If refresh happened to rotate before logout acquired lock, verify that the rotated successor token is also revoked
        String rotatedToken = rotatedTokenRef.get();
        if (rotatedToken != null) {
            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rotatedToken)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_REFRESH_TOKEN)));
        }

        // The original token must also fail refresh
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, oldRawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_REFRESH_TOKEN)));
    }

    @Test
    @DisplayName("Login sets a session refresh cookie without persistent Max-Age or Expires")
    void loginSetsSessionRefreshCookie() throws Exception {
        String rawPassword = "SessionLoginPass123!";
        User user = new User(UUID.randomUUID(), "session.cookie.user@example.com", passwordEncoder.encode(rawPassword), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "session.cookie.user@example.com",
                                  "password": "SessionLoginPass123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(AuthController.REFRESH_COOKIE_NAME))
                .andExpect(cookie().httpOnly(AuthController.REFRESH_COOKIE_NAME, true))
                .andExpect(cookie().path(AuthController.REFRESH_COOKIE_NAME, "/api/auth"))
                .andReturn();

        Cookie refreshCookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.getMaxAge()).isEqualTo(-1);

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("ledgerguard_refresh_token=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("Path=/api/auth");
        assertThat(setCookie).doesNotContain("Max-Age");
        assertThat(setCookie).doesNotContain("Expires");
    }

    @Test
    @DisplayName("Refreshing rotates to another session cookie without positive Max-Age or Expires")
    void refreshRotatesToAnotherSessionCookie() throws Exception {
        String rawPassword = "RotateSessionPass123!";
        User user = new User(UUID.randomUUID(), "rotate.session@example.com", passwordEncoder.encode(rawPassword), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        // 1. Initial login returns session cookie
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "rotate.session@example.com",
                                  "password": "RotateSessionPass123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        Cookie loginCookie = loginResult.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
        assertThat(loginCookie).isNotNull();
        assertThat(loginCookie.getMaxAge()).isEqualTo(-1);

        // 2. Refresh with that cookie
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(loginCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andReturn();

        Cookie rotatedCookie = refreshResult.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
        assertThat(rotatedCookie).isNotNull();
        assertThat(rotatedCookie.getValue()).isNotEqualTo(loginCookie.getValue());
        assertThat(rotatedCookie.getMaxAge()).isEqualTo(-1);

        String refreshSetCookie = refreshResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(refreshSetCookie).isNotNull();
        assertThat(refreshSetCookie).contains("ledgerguard_refresh_token=");
        assertThat(refreshSetCookie).contains("HttpOnly");
        assertThat(refreshSetCookie).contains("SameSite=Strict");
        assertThat(refreshSetCookie).contains("Path=/api/auth");
        assertThat(refreshSetCookie).doesNotContain("Max-Age");
        assertThat(refreshSetCookie).doesNotContain("Expires");
    }

    @Test
    @DisplayName("Logout deletes refresh cookie with Max-Age=0")
    void logoutDeletesRefreshCookie() throws Exception {
        User user = new User(UUID.randomUUID(), "logout.delete@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        RefreshTokenService.GeneratedToken token = refreshTokenService.createRefreshToken(user);

        MvcResult result = mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, token.rawToken())))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthController.REFRESH_COOKIE_NAME, 0))
                .andReturn();

        String logoutSetCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(logoutSetCookie).isNotNull();
        assertThat(logoutSetCookie).contains("Max-Age=0");
    }

    @Test
    @DisplayName("Server-side refresh token in database still preserves 7-day expiration semantics")
    void serverSideRefreshTokenStillExpiresAfterConfigured7Days() {
        User user = new User(UUID.randomUUID(), "db.ttl.user@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        Instant before = Instant.now();
        RefreshTokenService.GeneratedToken token = refreshTokenService.createRefreshToken(user);
        Instant after = Instant.now();

        RefreshToken entity = token.entity();
        assertThat(entity.getExpiresAt())
                .isAfterOrEqualTo(before.plus(Duration.ofDays(7)))
                .isBeforeOrEqualTo(after.plus(Duration.ofDays(7)));

        // Token is valid now
        assertThat(entity.isExpired(Instant.now())).isFalse();

        // Token is expired after 8 days
        Instant future = Instant.now().plus(Duration.ofDays(8));
        assertThat(entity.isExpired(future)).isTrue();
    }

    @Test
    @DisplayName("Reload and session restoration remains functional while a valid session cookie is present")
    void sessionRestorationRemainsFunctionalWhileValidSessionCookieIsPresent() throws Exception {
        String rawPassword = "RestoreSessionPass123!";
        User user = new User(UUID.randomUUID(), "restore.session@example.com", passwordEncoder.encode(rawPassword), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        // Login gives session cookie
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "restore.session@example.com",
                                  "password": "RestoreSessionPass123!"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        Cookie sessionCookie = loginResult.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
        assertThat(sessionCookie).isNotNull();

        // Simulate page reload / startup restoration
        MvcResult restoreResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andExpect(jsonPath("$.user.email", is("restore.session@example.com")))
                .andReturn();

        // Extract fresh access token from restore response
        String responseBody = restoreResult.getResponse().getContentAsString();
        String accessToken = com.jayway.jsonpath.JsonPath.read(responseBody, "$.accessToken");

        // Use restored access token to call protected endpoint GET /api/auth/me
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("restore.session@example.com")))
                .andExpect(jsonPath("$.role", is("CUSTOMER")));
    }
}
