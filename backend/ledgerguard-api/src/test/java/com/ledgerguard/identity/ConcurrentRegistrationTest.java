package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.shared.error.ApiErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ConcurrentRegistrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Concurrent registration with the same email: exactly one succeeds (201), the other returns controlled 400 (no 500 or SQL leak)")
    void concurrentRegistrationWithSameEmailIsSafe() throws Exception {
        String sameEmail = "race.registration@example.com";
        String payload = """
                {
                  "email": "%s",
                  "password": "ValidPassword1234!",
                  "role": "CUSTOMER"
                }
                """.formatted(sameEmail);

        int concurrency = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger controlledDuplicateCount = new AtomicInteger(0);
        AtomicInteger serverErrorCount = new AtomicInteger(0);

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                MvcResult res = mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();
                int status = res.getResponse().getStatus();
                String body = res.getResponse().getContentAsString();
                if (status == 201) {
                    successCount.incrementAndGet();
                } else if (status == 400 && body.contains(ApiErrorCode.EMAIL_ALREADY_REGISTERED)) {
                    controlledDuplicateCount.incrementAndGet();
                } else if (status == 500) {
                    serverErrorCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Handled
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                MvcResult res = mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();
                int status = res.getResponse().getStatus();
                String body = res.getResponse().getContentAsString();
                if (status == 201) {
                    successCount.incrementAndGet();
                } else if (status == 400 && body.contains(ApiErrorCode.EMAIL_ALREADY_REGISTERED)) {
                    controlledDuplicateCount.incrementAndGet();
                } else if (status == 500) {
                    serverErrorCount.incrementAndGet();
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
        assertThat(controlledDuplicateCount.get()).isEqualTo(1);
        assertThat(serverErrorCount.get()).isEqualTo(0);
        assertThat(userRepository.findByEmail(sameEmail)).isPresent();
    }
}
