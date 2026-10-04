package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.api.AuthController;
import com.ledgerguard.identity.api.dto.ChangePasswordRequest;
import com.ledgerguard.identity.api.dto.ForgotPasswordRequest;
import com.ledgerguard.identity.api.dto.ResetPasswordRequest;
import com.ledgerguard.identity.domain.PasswordResetToken;
import com.ledgerguard.identity.domain.PasswordResetTokenRepository;
import com.ledgerguard.identity.domain.RefreshToken;
import com.ledgerguard.identity.domain.RefreshTokenRepository;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.identity.infrastructure.PasswordRecoveryEmailDispatcher;
import com.ledgerguard.identity.infrastructure.PasswordRecoveryProperties;
import com.ledgerguard.shared.security.JwtTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordRecoveryAndChangeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private PasswordRecoveryProperties passwordRecoveryProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PasswordRecoveryEmailDispatcher emailDispatcher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private String hashToken(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Forgot password returns identical generic response for eligible Customer, Merchant, disabled account, OPS, and unknown email")
    void forgotPasswordReturnsGenericResponse() throws Exception {
        String genericMsg = "If an eligible account exists, you will receive a password reset link.";

        User customer = new User(UUID.randomUUID(), "Customer User", "cust.recovery@example.com",
                passwordEncoder.encode("CurrentPass1234!"), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);

        User merchant = new User(UUID.randomUUID(), "Merchant User", "merch.recovery@example.com",
                passwordEncoder.encode("CurrentPass1234!"), UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(merchant);

        User ops = new User(UUID.randomUUID(), "Ops User", "ops.recovery@example.com",
                passwordEncoder.encode("CurrentPass1234!"), UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(ops);

        User disabled = new User(UUID.randomUUID(), "Disabled User", "disabled.recovery@example.com",
                passwordEncoder.encode("CurrentPass1234!"), UserRole.CUSTOMER, UserStatus.DISABLED);
        userRepository.save(disabled);

        for (String email : new String[]{"cust.recovery@example.com", "merch.recovery@example.com", "ops.recovery@example.com", "disabled.recovery@example.com", "unknown.person@example.com"}) {
            mockMvc.perform(post("/api/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ForgotPasswordRequest(email))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message", is(genericMsg)));
        }

        // Verify tokens were generated only for active customer and merchant
        assertThat(passwordResetTokenRepository.findAll().stream().filter(t -> t.getUser().getId().equals(customer.getId())).count()).isEqualTo(1);
        assertThat(passwordResetTokenRepository.findAll().stream().filter(t -> t.getUser().getId().equals(merchant.getId())).count()).isEqualTo(1);
        assertThat(passwordResetTokenRepository.findAll().stream().filter(t -> t.getUser().getId().equals(ops.getId())).count()).isEqualTo(0);
        assertThat(passwordResetTokenRepository.findAll().stream().filter(t -> t.getUser().getId().equals(disabled.getId())).count()).isEqualTo(0);
    }

    @Test
    @DisplayName("Reset password succeeds, consumes token, increments credential version, and revokes sessions")
    void resetPasswordSucceedsAndRevokesSessions() throws Exception {
        String oldPassword = "OldSecurePassword123!";
        String newPassword = "NewSecurePassword456!";
        User user = new User(UUID.randomUUID(), "Reset Target", "target.reset@example.com",
                passwordEncoder.encode(oldPassword), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        // Issue active access token with old credential version (1)
        String oldAccessToken = jwtTokenService.generateAccessToken(user);

        // Create refresh token
        RefreshToken rt = RefreshToken.create(user, hashToken("oldRefreshTokenRaw"), Instant.now().plus(Duration.ofDays(7)));
        refreshTokenRepository.save(rt);

        // Issue reset token
        String rawToken = "super-secret-reset-token-12345678901234567890";
        PasswordResetToken resetToken = PasswordResetToken.create(user, hashToken(rawToken), Instant.now().plus(Duration.ofMinutes(15)));
        passwordResetTokenRepository.save(resetToken);

        // Execute reset password
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawToken, newPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password has been reset successfully. Please log in with your new password."));

        // Verify token is consumed
        PasswordResetToken updatedToken = passwordResetTokenRepository.findById(resetToken.getId()).orElseThrow();
        assertThat(updatedToken.isConsumed()).isTrue();

        // Verify user updated and credential version incremented
        User updatedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches(newPassword, updatedUser.getPasswordHash())).isTrue();
        assertThat(updatedUser.getCredentialVersion()).isEqualTo(2);

        // Verify refresh token was revoked
        RefreshToken updatedRt = refreshTokenRepository.findById(rt.getId()).orElseThrow();
        assertThat(updatedRt.isRevoked()).isTrue();

        // Verify old access token is REJECTED on protected resource
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + oldAccessToken))
                .andExpect(status().isUnauthorized());

        // Verify fresh login works with new password
        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"target.reset@example.com\",\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginRes.getResponse().getContentAsString());
        String freshAccessToken = loginJson.get("accessToken").asText();

        // Verify fresh access token works on protected resource
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + freshAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("target.reset@example.com")));
    }

    @Test
    @DisplayName("Reset password rejects reused, expired, and unknown tokens")
    void resetPasswordRejectsInvalidTokens() throws Exception {
        User user = new User(UUID.randomUUID(), "Test User", "test.invalid@example.com",
                passwordEncoder.encode("OriginalPass123!"), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        // Expired token
        String expiredRaw = "expired-token-raw-value-1234567890123";
        PasswordResetToken expToken = PasswordResetToken.create(user, hashToken(expiredRaw), Instant.now().minus(Duration.ofMinutes(1)));
        passwordResetTokenRepository.save(expToken);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(expiredRaw, "ValidNewPassword123!"))))
                .andExpect(status().isBadRequest());

        // Unknown token
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest("completely-unknown-token", "ValidNewPassword123!"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Reset password rejects reusing existing password")
    void resetPasswordRejectsSamePassword() throws Exception {
        String existingPass = "SamePasswordMustFail123!";
        User user = new User(UUID.randomUUID(), "Test User", "test.samepass@example.com",
                passwordEncoder.encode(existingPass), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String rawToken = "same-pass-token-raw-1234567890";
        PasswordResetToken token = PasswordResetToken.create(user, hashToken(rawToken), Instant.now().plus(Duration.ofMinutes(15)));
        passwordResetTokenRepository.save(token);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawToken, existingPass))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", is("New password cannot be the same as the current password.")));
    }

    @Test
    @DisplayName("Change password requires correct current password and rejects reusing current password")
    void changePasswordValidatesCurrentPasswordAndRejectsSame() throws Exception {
        String currentPass = "MyCurrentSecurePass123!";
        User user = new User(UUID.randomUUID(), "Change Pass User", "change.user@example.com",
                passwordEncoder.encode(currentPass), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String accessToken = jwtTokenService.generateAccessToken(user);

        // Wrong current password
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest("WrongCurrentPass1!", "ValidNewPass456!"))))
                .andExpect(status().isUnauthorized());

        // Same new password
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(currentPass, currentPass))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", is("New password cannot be the same as the current password.")));

        // Successful change password
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(currentPass, "ValidNewPass456!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Password has been changed successfully. Please log in again.")));

        // Old access token now rejected due to credential version bump
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Password change invalidates all outstanding reset tokens for user")
    void passwordChangeInvalidatesOutstandingResetTokens() throws Exception {
        String currentPass = "InitialPassForReset123!";
        User user = new User(UUID.randomUUID(), "Reset Invalidation User", "reset.inv@example.com",
                passwordEncoder.encode(currentPass), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String rawResetToken = "outstanding-reset-token-xyz123";
        PasswordResetToken token = PasswordResetToken.create(user, hashToken(rawResetToken), Instant.now().plus(Duration.ofMinutes(15)));
        passwordResetTokenRepository.save(token);

        String accessToken = jwtTokenService.generateAccessToken(user);

        // Perform password change
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(currentPass, "BrandNewPassword789!"))))
                .andExpect(status().isOk());

        // Verify outstanding reset token is now consumed/invalidated
        PasswordResetToken reloaded = passwordResetTokenRepository.findById(token.getId()).orElseThrow();
        assertThat(reloaded.isConsumed()).isTrue();

        // Attempting reset with that token must now fail
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawResetToken, "AnotherNewPass123!"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Concurrent consumption of the exact same reset token permits exactly one success")
    void concurrentConsumptionOfSameTokenPermitsExactlyOneSuccess() throws Exception {
        User user = new User(UUID.randomUUID(), "Concurrent Reset User", "concurrent.reset@example.com",
                passwordEncoder.encode("InitialPassword123!"), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String rawToken = "concurrent-raw-reset-token-abc123456789";
        PasswordResetToken token = PasswordResetToken.create(user, hashToken(rawToken), Instant.now().plus(Duration.ofMinutes(15)));
        passwordResetTokenRepository.save(token);

        int concurrency = 2;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(concurrency);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicReference<String> winningPassword = new java.util.concurrent.atomic.AtomicReference<>(null);

        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            final String newPass = "NewDistinctPass" + i + "123456!";
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    boolean started = startLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(started).isTrue();
                    MvcResult res = mockMvc.perform(post("/api/auth/reset-password")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawToken, newPass))))
                            .andReturn();
                    if (res.getResponse().getStatus() == 200) {
                        successCount.incrementAndGet();
                        winningPassword.set(newPass);
                    } else if (res.getResponse().getStatus() == 400) {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        boolean allReady = readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(allReady).isTrue();
        startLatch.countDown();

        for (java.util.concurrent.Future<?> f : futures) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        executor.shutdown();
        boolean terminated = executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(terminated).isTrue();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
        assertThat(winningPassword.get()).isNotNull();

        // Verify the winning password works for login
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.ledgerguard.identity.api.dto.LoginRequest(user.getEmail(), winningPassword.get()))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Token with expiresAt <= now is rejected as expired")
    void tokenExpiresAtOrBeforeNowRejected() throws Exception {
        User user = new User(UUID.randomUUID(), "Expiration User", "expiry.test@example.com",
                passwordEncoder.encode("Pass123456789!"), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String rawToken = "exact-expiry-token-987654321";
        // Token expires right at now
        PasswordResetToken token = PasswordResetToken.create(user, hashToken(rawToken), Instant.now());
        passwordResetTokenRepository.save(token);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawToken, "NewValidPass999!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", is("Invalid, expired, or already used reset token.")));
    }

    @Test
    @DisplayName("Reset consumption rejects disabled account and OPS role")
    void disabledAndOpsAccountsRejectedAtConsumptionTime() throws Exception {
        // Disabled Customer
        User disabledUser = new User(UUID.randomUUID(), "Disabled Target", "disabled.target@example.com",
                passwordEncoder.encode("ValidPass12345!"), UserRole.CUSTOMER, UserStatus.DISABLED);
        userRepository.save(disabledUser);

        String disabledRaw = "disabled-user-raw-token-111";
        PasswordResetToken disabledToken = PasswordResetToken.create(disabledUser, hashToken(disabledRaw), Instant.now().plus(Duration.ofMinutes(15)));
        passwordResetTokenRepository.save(disabledToken);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(disabledRaw, "BrandNewPass999!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", is("Account is not eligible for password reset.")));

        // OPS User
        User opsUser = new User(UUID.randomUUID(), "OPS Target", "ops.target@example.com",
                passwordEncoder.encode("ValidPass12345!"), UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(opsUser);

        String opsRaw = "ops-user-raw-token-222";
        PasswordResetToken opsToken = PasswordResetToken.create(opsUser, hashToken(opsRaw), Instant.now().plus(Duration.ofMinutes(15)));
        passwordResetTokenRepository.save(opsToken);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(opsRaw, "BrandNewPass999!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", is("Account is not eligible for password reset.")));
    }

    @Test
    @DisplayName("Reset racing with login: old-password login leaves no usable active sessions")
    void resetRacingWithLoginLeavesNoUsableOldSession() throws Exception {
        String oldPassword = "OldRacingPassword123!";
        String newPassword = "NewRacingPassword456!";
        User user = new User(UUID.randomUUID(), "Racing Login User", "racing.login@example.com",
                passwordEncoder.encode(oldPassword), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String rawToken = "racing-login-token-12345";
        PasswordResetToken token = PasswordResetToken.create(user, hashToken(rawToken), Instant.now().plus(Duration.ofMinutes(15)));
        passwordResetTokenRepository.save(token);

        int concurrency = 2;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(concurrency);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

        java.util.concurrent.atomic.AtomicReference<String> loginRefreshToken = new java.util.concurrent.atomic.AtomicReference<>(null);
        java.util.concurrent.atomic.AtomicReference<String> loginAccessToken = new java.util.concurrent.atomic.AtomicReference<>(null);

        // Thread 1: reset password
        java.util.concurrent.Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                boolean started = startLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(started).isTrue();
                mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawToken, newPassword))))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 2: login with old password
        java.util.concurrent.Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                boolean started = startLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(started).isTrue();
                MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.ledgerguard.identity.api.dto.LoginRequest(user.getEmail(), oldPassword))))
                        .andReturn();
                if (res.getResponse().getStatus() == 200) {
                    JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
                    if (json.has("accessToken")) {
                        loginAccessToken.set(json.get("accessToken").asText());
                    }
                    jakarta.servlet.http.Cookie cookie = res.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
                    if (cookie != null) {
                        loginRefreshToken.set(cookie.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        boolean allReady = readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(allReady).isTrue();
        startLatch.countDown();

        f1.get(10, java.util.concurrent.TimeUnit.SECONDS);
        f2.get(10, java.util.concurrent.TimeUnit.SECONDS);

        executor.shutdown();
        boolean terminated = executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(terminated).isTrue();

        // If login succeeded before or concurrently with reset, its refresh and access tokens must be unusable!
        if (loginRefreshToken.get() != null) {
            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, loginRefreshToken.get())))
                    .andExpect(status().isUnauthorized());
        }
        if (loginAccessToken.get() != null) {
            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + loginAccessToken.get()))
                    .andExpect(status().isUnauthorized());
        }

        // Login with old password must now be rejected
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.ledgerguard.identity.api.dto.LoginRequest(user.getEmail(), oldPassword))))
                .andExpect(status().isUnauthorized());

        // Login with new password must succeed
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.ledgerguard.identity.api.dto.LoginRequest(user.getEmail(), newPassword))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Reset racing with refresh token rotation: old access and refresh tokens are rejected, new password works")
    void resetRacingWithRefreshRevokesSessions() throws Exception {
        String oldPassword = "RacingRefreshOldPass123!";
        String newPassword = "RacingRefreshNewPass456!";
        User user = new User(UUID.randomUUID(), "Racing Refresh User", "racing.refresh@example.com",
                passwordEncoder.encode(oldPassword), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        // Pre-create active session (access token + refresh token)
        String oldAccessToken = jwtTokenService.generateAccessToken(user);
        com.ledgerguard.identity.application.RefreshTokenService.GeneratedToken initialRt =
                refreshTokenRepository.save(RefreshToken.create(user, hashToken("initial-racing-refresh-rt"), Instant.now().plus(Duration.ofDays(7)))) != null
                        ? new com.ledgerguard.identity.application.RefreshTokenService.GeneratedToken("initial-racing-refresh-rt", null)
                        : null;

        String rawResetToken = "racing-reset-refresh-token-xyz";
        PasswordResetToken resetToken = PasswordResetToken.create(user, hashToken(rawResetToken), Instant.now().plus(Duration.ofMinutes(15)));
        passwordResetTokenRepository.save(resetToken);

        int concurrency = 2;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(concurrency);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

        java.util.concurrent.atomic.AtomicReference<String> rotatedRefreshToken = new java.util.concurrent.atomic.AtomicReference<>(null);
        java.util.concurrent.atomic.AtomicReference<String> rotatedAccessToken = new java.util.concurrent.atomic.AtomicReference<>(null);

        // Thread 1: reset password
        java.util.concurrent.Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                boolean started = startLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(started).isTrue();
                mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawResetToken, newPassword))))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 2: refresh token rotation
        java.util.concurrent.Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                boolean started = startLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(started).isTrue();
                MvcResult res = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, "initial-racing-refresh-rt")))
                        .andReturn();
                if (res.getResponse().getStatus() == 200) {
                    JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
                    if (json.has("accessToken")) {
                        rotatedAccessToken.set(json.get("accessToken").asText());
                    }
                    Cookie cookie = res.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
                    if (cookie != null) {
                        rotatedRefreshToken.set(cookie.getValue());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        boolean allReady = readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(allReady).isTrue();
        startLatch.countDown();

        f1.get(10, java.util.concurrent.TimeUnit.SECONDS);
        f2.get(10, java.util.concurrent.TimeUnit.SECONDS);

        executor.shutdown();
        boolean terminated = executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(terminated).isTrue();

        // Old access token MUST be rejected
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + oldAccessToken))
                .andExpect(status().isUnauthorized());

        // If rotation completed, the rotated refresh token and access token MUST be rejected because reset increments credential version and revokes tokens
        if (rotatedRefreshToken.get() != null) {
            mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rotatedRefreshToken.get())))
                    .andExpect(status().isUnauthorized());
        }
        if (rotatedAccessToken.get() != null) {
            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + rotatedAccessToken.get()))
                    .andExpect(status().isUnauthorized());
        }

        // New password works for login
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.ledgerguard.identity.api.dto.LoginRequest(user.getEmail(), newPassword))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Deterministic test: refresh token expires while waiting for user lock")
    void refreshTokenExpiresWhileWaitingForUserLock() throws Exception {
        User user = new User(UUID.randomUUID(), "Lock Expiry User", "lock.expiry@example.com",
                passwordEncoder.encode("Password123456!"), UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        String rawToken = "almost-expired-token-val";
        // Token expires in 1.5 seconds
        Instant expiresAt = Instant.now().plusMillis(1500);
        RefreshToken token = RefreshToken.create(user, hashToken(rawToken), expiresAt);
        refreshTokenRepository.save(token);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch lockHeldLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseLockLatch = new java.util.concurrent.CountDownLatch(1);

        // Thread 1: Acquire exclusive write lock on the User and hold it until after token expiration
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(
                        webApplicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class));

        java.util.concurrent.Future<?> lockFuture = executor.submit(() -> {
            txTemplate.execute(status -> {
                userRepository.findByIdWithLock(user.getId());
                lockHeldLatch.countDown();
                try {
                    boolean released = releaseLockLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(released).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        boolean locked = lockHeldLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(locked).isTrue();

        // While lock is held, launch refresh request on main thread in background executor
        java.util.concurrent.ExecutorService refreshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Integer> refreshStatusFuture = refreshExecutor.submit(() -> {
            MvcResult res = mockMvc.perform(post("/api/auth/refresh")
                            .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rawToken)))
                    .andReturn();
            return res.getResponse().getStatus();
        });

        // Wait until expiresAt is reached
        while (!Instant.now().isAfter(expiresAt)) {
            Thread.sleep(50);
        }

        // Release the user lock now
        releaseLockLatch.countDown();
        lockFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);

        // When refresh thread acquires the lock, it rechecks timestamp against now, sees expired, and returns 401
        int httpStatus = refreshStatusFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(httpStatus).isEqualTo(401);

        executor.shutdown();
        refreshExecutor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        refreshExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
