package com.ledgerguard.resilience;

import com.ledgerguard.funding.infrastructure.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PspClientResilienceUnitTest {

    private PspClient pspClient;
    private MockRestServiceServer mockServer;
    private ProviderResilienceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ProviderResilienceProperties();
        // Zero jitter for deterministic testing
        properties.getRetry().getCreate().setJitterFactor(0.0);
        properties.getRetry().getCreate().setInitialBackoff(Duration.ofMillis(10));
        properties.getRetry().getCreate().setMaxBackoff(Duration.ofMillis(20));

        properties.getRetry().getStatus().setJitterFactor(0.0);
        properties.getRetry().getStatus().setInitialBackoff(Duration.ofMillis(10));
        properties.getRetry().getStatus().setMaxBackoff(Duration.ofMillis(20));

        properties.getCircuitBreaker().setSlidingWindowSize(5);
        properties.getCircuitBreaker().setMinimumNumberOfCalls(2);
        properties.getCircuitBreaker().setFailureRateThreshold(50.0f);
        properties.getCircuitBreaker().setWaitDurationInOpenState(Duration.ofMillis(100));

        pspClient = new PspClient(
                "http://test-psp:8081",
                1000,
                1000,
                "http://localhost:8080/api/provider/webhooks",
                null,
                properties
        );

        RestClient.Builder builder = RestClient.builder().baseUrl("http://test-psp:8081");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        ReflectionTestUtils.setField(pspClient, "restClient", restClient);
    }

    @Test
    @DisplayName("GET 404 returns Optional.empty() without retrying or harming circuit breaker")
    void testGet404NoRetryNoCircuitHarm() {
        UUID clientOpId = UUID.randomUUID();
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations/by-client/" + clientOpId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<PspOperationResponse> resp = pspClient.getOperationByClientOperationId(clientOpId);

        assertThat(resp).isEmpty();
        mockServer.verify();
        assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("GET retries on 500 and eventually succeeds")
    void testGet500RetryEventualSuccess() {
        UUID clientOpId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        String jsonSuccess = "{\"providerOperationId\":\"" + providerOpId + "\",\"clientOperationId\":\"" + clientOpId + "\",\"operationType\":\"CREDIT\",\"amountMinor\":\"1000\",\"currency\":\"INR\",\"status\":\"SUCCEEDED\",\"createdAt\":\"2026-09-04T12:00:00Z\",\"completedAt\":\"2026-09-04T12:00:01Z\",\"replayed\":false}";

        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations/by-client/" + clientOpId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations/by-client/" + clientOpId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(jsonSuccess, MediaType.APPLICATION_JSON));

        Optional<PspOperationResponse> resp = pspClient.getOperationByClientOperationId(clientOpId);

        assertThat(resp).isPresent();
        assertThat(resp.get().providerOperationId()).isEqualTo(providerOpId);
        mockServer.verify();
        assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("CREATE retries on temporary 500 and succeeds on 2nd attempt")
    void testCreateTemporary500EventualSuccess() {
        UUID clientOpId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        String tempFailureProblem = "{\"type\":\"urn:ledgerguard:psp:error:temporary-failure\",\"title\":\"Temporary Error\",\"status\":500}";
        String jsonSuccess = "{\"providerOperationId\":\"" + providerOpId + "\",\"clientOperationId\":\"" + clientOpId + "\",\"operationType\":\"CREDIT\",\"amountMinor\":\"5000\",\"currency\":\"INR\",\"status\":\"SUCCEEDED\",\"createdAt\":\"2026-09-04T12:00:00Z\",\"completedAt\":\"2026-09-04T12:00:01Z\",\"replayed\":false}";

        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(tempFailureProblem));

        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(jsonSuccess, MediaType.APPLICATION_JSON));

        PspOperationResponse resp = pspClient.createOperation(clientOpId, "CREDIT", "5000", "INR");

        assertThat(resp).isNotNull();
        assertThat(resp.providerOperationId()).isEqualTo(providerOpId);
        mockServer.verify();
        assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("CREATE exhausts temporary 500 when all attempts return temporary-failure (definite failure)")
    void testCreateTemporary500ExhaustedDefiniteFailure() {
        UUID clientOpId = UUID.randomUUID();
        String tempFailureProblem = "{\"type\":\"urn:ledgerguard:psp:error:temporary-failure\",\"title\":\"Temporary Error\",\"status\":500}";

        mockServer.expect(ExpectedCount.times(3), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(tempFailureProblem));

        assertThatThrownBy(() -> pspClient.createOperation(clientOpId, "CREDIT", "5000", "INR"))
                .isInstanceOf(PspProtocolException.class)
                .hasMessageContaining("5xx server error")
                .matches(ex -> ((PspProtocolException) ex).getStatusCode() == 500
                        && "urn:ledgerguard:psp:error:temporary-failure".equals(((PspProtocolException) ex).getProviderErrorType()));

        mockServer.verify();
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Mixed outcome: attempt 1 generic 500 (ambiguous), attempts 2 & 3 temporary 500 -> surfaces PspAmbiguousOutcomeException")
    void testCreateMixedOutcomeSurfacesAmbiguous() {
        UUID clientOpId = UUID.randomUUID();
        String generic500 = "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500}";
        String tempFailureProblem = "{\"type\":\"urn:ledgerguard:psp:error:temporary-failure\",\"title\":\"Temporary Error\",\"status\":500}";

        // Attempt 1: generic 500 (ambiguous)
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(generic500));

        // Attempt 2: temporary failure
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(tempFailureProblem));

        // Attempt 3: temporary failure
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(tempFailureProblem));

        assertThatThrownBy(() -> pspClient.createOperation(clientOpId, "CREDIT", "5000", "INR"))
                .isInstanceOf(PspAmbiguousOutcomeException.class)
                .hasMessageContaining("Logical provider CREATE outcome is ambiguous after 3 attempts")
                .matches(ex -> ((PspAmbiguousOutcomeException) ex).getAttemptsMade() == 3);

        mockServer.verify();
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("TIMEOUT_AFTER_SUCCESS Funding replay: retry succeeds with existing operation row")
    void testTimeoutAfterSuccessReplay() {
        UUID clientOpId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        String jsonSuccess = "{\"providerOperationId\":\"" + providerOpId + "\",\"clientOperationId\":\"" + clientOpId + "\",\"operationType\":\"CREDIT\",\"amountMinor\":\"5000\",\"currency\":\"INR\",\"status\":\"SUCCEEDED\",\"createdAt\":\"2026-09-04T12:00:00Z\",\"completedAt\":\"2026-09-04T12:00:01Z\",\"replayed\":true}";

        // Attempt 1: 504 Gateway Timeout (ambiguous)
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        // Attempt 2: Success on idempotent replay (replayed: true)
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(jsonSuccess, MediaType.APPLICATION_JSON));

        PspOperationResponse resp = pspClient.createOperation(clientOpId, "CREDIT", "5000", "INR");

        assertThat(resp).isNotNull();
        assertThat(resp.providerOperationId()).isEqualTo(providerOpId);
        assertThat(resp.replayed()).isTrue();
        mockServer.verify();
        assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Raw HTTP execution boundary: transaction must not be active during physical attempts across CREATE and GET retries")
    void testRawHttpExecutionBoundaryNoActiveTransaction() {
        UUID clientOpId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        String jsonSuccess = "{\"providerOperationId\":\"" + providerOpId + "\",\"clientOperationId\":\"" + clientOpId + "\",\"operationType\":\"CREDIT\",\"amountMinor\":\"5000\",\"currency\":\"INR\",\"status\":\"SUCCEEDED\",\"createdAt\":\"2026-09-04T12:00:00Z\",\"completedAt\":\"2026-09-04T12:00:01Z\",\"replayed\":false}";
        String tempFailureProblem = "{\"type\":\"urn:ledgerguard:psp:error:temporary-failure\",\"title\":\"Temporary Error\",\"status\":500}";

        // Verify CREATE attempt 1, 2, 3 have isActualTransactionActive() == false
        mockServer.expect(ExpectedCount.times(2), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body(tempFailureProblem).createResponse(request);
                });
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return withSuccess(jsonSuccess, MediaType.APPLICATION_JSON).createResponse(request);
                });

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        PspOperationResponse resp = pspClient.createOperation(clientOpId, "CREDIT", "5000", "INR");
        assertThat(resp).isNotNull();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        mockServer.verify();
        mockServer.reset();

        // Verify GET status attempt 1, 2, 3 have isActualTransactionActive() == false
        mockServer.expect(ExpectedCount.times(2), requestTo("http://test-psp:8081/api/provider/operations/by-client/" + clientOpId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body(tempFailureProblem).createResponse(request);
                });
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations/by-client/" + clientOpId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return withSuccess(jsonSuccess, MediaType.APPLICATION_JSON).createResponse(request);
                });

        Optional<PspOperationResponse> getResp = pspClient.getOperationByClientOperationId(clientOpId);
        assertThat(getResp).isPresent();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        mockServer.verify();
    }

    @Test
    @DisplayName("Breaker counts logical call, not each physical retry attempt")
    void testBreakerCountsLogicalCallNotEachRetry() {
        UUID clientOpId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        String jsonSuccess = "{\"providerOperationId\":\"" + providerOpId + "\",\"clientOperationId\":\"" + clientOpId + "\",\"operationType\":\"CREDIT\",\"amountMinor\":\"5000\",\"currency\":\"INR\",\"status\":\"SUCCEEDED\",\"createdAt\":\"2026-09-04T12:00:00Z\",\"completedAt\":\"2026-09-04T12:00:01Z\",\"replayed\":false}";
        String tempFailureProblem = "{\"type\":\"urn:ledgerguard:psp:error:temporary-failure\",\"title\":\"Temporary Error\",\"status\":500}";

        // Call 1: attempt 1 fails 500, attempt 2 fails 500, attempt 3 succeeds
        mockServer.expect(ExpectedCount.times(2), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(tempFailureProblem));
        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(jsonSuccess, MediaType.APPLICATION_JSON));

        pspClient.createOperation(clientOpId, "CREDIT", "5000", "INR");

        // Circuit metrics: exactly 1 logical success, 0 logical failures! (Physical retries do NOT inflate failure count)
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
        mockServer.verify();
        mockServer.reset();

        // Call 2: 3 timeouts -> logical call fails
        UUID failOpId = UUID.randomUUID();
        mockServer.expect(ExpectedCount.times(3), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> pspClient.createOperation(failOpId, "CREDIT", "5000", "INR"))
                .isInstanceOf(PspAmbiguousOutcomeException.class);

        // Circuit metrics: exactly 1 logical success, exactly 1 logical failure (not 3 failures!)
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        mockServer.verify();
    }

    @Test
    @DisplayName("Non-retryable 409 conflicting-replay immediately throws without retrying")
    void testCreate409ConflictingReplayNoRetry() {
        UUID clientOpId = UUID.randomUUID();
        String conflictProblem = "{\"type\":\"urn:ledgerguard:psp:error:conflicting-replay\",\"title\":\"Conflict\",\"status\":409}";

        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(conflictProblem));

        assertThatThrownBy(() -> pspClient.createOperation(clientOpId, "CREDIT", "5000", "INR"))
                .isInstanceOf(PspProtocolException.class)
                .matches(ex -> ((PspProtocolException) ex).getStatusCode() == 409
                        && "urn:ledgerguard:psp:error:conflicting-replay".equals(((PspProtocolException) ex).getProviderErrorType()));

        mockServer.verify();
        // 409 is ignored by circuit breaker
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("Bulkhead saturation on psp-status does NOT open shared psp-remote circuit breaker")
    void testBulkheadSaturationDoesNotTripCircuitBreaker() throws Exception {
        // Saturate status bulkhead permits (20 permits)
        int maxPermits = 20;
        CountDownLatch acquiredLatch = new CountDownLatch(maxPermits);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        for (int i = 0; i < maxPermits; i++) {
            new Thread(() -> {
                boolean acquired = pspClient.getStatusBulkhead().tryAcquirePermission();
                if (acquired) {
                    acquiredLatch.countDown();
                    try {
                        releaseLatch.await();
                    } catch (InterruptedException ignored) {
                    } finally {
                        pspClient.getStatusBulkhead().onComplete();
                    }
                }
            }).start();
        }

        assertThat(acquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // 21st status query fails fast with BulkheadFullException mapped to PspCallRejectedException
        UUID clientOpId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> pspClient.getOperationByClientOperationId(clientOpId))
                    .isInstanceOf(PspCallRejectedException.class)
                    .matches(ex -> ((PspCallRejectedException) ex).getReason() == PspCallRejectedException.Reason.BULKHEAD_FULL);
        }

        // Verify circuit breaker remains CLOSED and failed calls metric is 0
        assertThat(pspClient.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(pspClient.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(0);

        // Verify create bulkhead is unaffected and can still process calls
        UUID createOpId = UUID.randomUUID();
        UUID providerOpId = UUID.randomUUID();
        String jsonSuccess = "{\"providerOperationId\":\"" + providerOpId + "\",\"clientOperationId\":\"" + createOpId + "\",\"operationType\":\"CREDIT\",\"amountMinor\":\"2000\",\"currency\":\"INR\",\"status\":\"SUCCEEDED\",\"createdAt\":\"2026-09-04T12:00:00Z\",\"completedAt\":\"2026-09-04T12:00:01Z\",\"replayed\":false}";

        mockServer.expect(ExpectedCount.once(), requestTo("http://test-psp:8081/api/provider/operations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(jsonSuccess, MediaType.APPLICATION_JSON));

        PspOperationResponse createResp = pspClient.createOperation(createOpId, "CREDIT", "2000", "INR");
        assertThat(createResp.providerOperationId()).isEqualTo(providerOpId);

        // Release status permits
        releaseLatch.countDown();
    }
}
