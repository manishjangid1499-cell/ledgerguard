package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.api.AuthController;
import com.ledgerguard.identity.application.RefreshTokenService;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ConcurrentRefreshTokenRotationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

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
    @DisplayName("Concurrent refresh requests using the exact same token: at most one succeeds, other fails safely")
    void concurrentRefreshTokenRotationIsSafe() throws Exception {
        User user = new User(UUID.randomUUID(), "concurrent.user@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);

        RefreshTokenService.GeneratedToken token = refreshTokenService.createRefreshToken(user);
        String rawToken = token.rawToken();

        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                MvcResult res = mockMvc.perform(post("/api/auth/refresh")
                                .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rawToken)))
                        .andReturn();
                if (res.getResponse().getStatus() == 200) {
                    successCount.incrementAndGet();
                } else if (res.getResponse().getStatus() == 401) {
                    failureCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Handled
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                MvcResult res = mockMvc.perform(post("/api/auth/refresh")
                                .cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, rawToken)))
                        .andReturn();
                if (res.getResponse().getStatus() == 200) {
                    successCount.incrementAndGet();
                } else if (res.getResponse().getStatus() == 401) {
                    failureCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Handled
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release both threads at the exact same instant

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
    }
}
