package com.ledgerguard.funding.infrastructure;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Spring RestClient wrapper for outbound HTTP integration with the external PSP simulator.
 * Programmatically decorated with Resilience4j CircuitBreaker, Bulkheads, and Retries.
 * <p>
 * Execution pipeline: CircuitBreaker -> Bulkhead -> Retry -> Raw HTTP
 */
@Component
public class PspClient {

    private static final Logger log = LoggerFactory.getLogger(PspClient.class);

    private final RestClient restClient;
    private final String webhookUrl;
    private final ObjectMapper objectMapper;

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead createBulkhead;
    private final Bulkhead statusBulkhead;
    private final Retry createRetry;
    private final Retry statusRetry;

    public PspClient(
            @Value("${ledgerguard.psp.base-url:http://localhost:8081}") String baseUrl,
            @Value("${ledgerguard.psp.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ledgerguard.psp.read-timeout-ms:2000}") int readTimeoutMs,
            @Value("${ledgerguard.psp.webhook-url:http://localhost:8080/api/provider/webhooks}") String webhookUrl,
            ObjectMapper objectMapper,
            ProviderResilienceProperties properties
    ) {
        this.webhookUrl = webhookUrl;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        ProviderResilienceProperties props = properties != null ? properties : new ProviderResilienceProperties();

        // 1. Circuit Breaker Configuration (Evaluates logical outcome)
        ProviderResilienceProperties.CircuitBreaker cbProps = props.getCircuitBreaker();
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbProps.getSlidingWindowSize())
                .minimumNumberOfCalls(cbProps.getMinimumNumberOfCalls())
                .failureRateThreshold(cbProps.getFailureRateThreshold())
                .waitDurationInOpenState(cbProps.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cbProps.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(cbProps.isAutomaticTransitionFromOpenToHalfOpenEnabled())
                .recordException(PspClient::isCircuitBreakerFailure)
                .ignoreException(PspClient::isCircuitBreakerIgnored)
                .build();
        this.circuitBreaker = CircuitBreaker.of(cbProps.getName(), cbConfig);

        // 2. Bulkhead Configurations (Semaphores)
        ProviderResilienceProperties.Bulkhead bhProps = props.getBulkhead();
        BulkheadConfig createBhConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(bhProps.getCreate().getMaxConcurrentCalls())
                .maxWaitDuration(bhProps.getCreate().getMaxWaitDuration())
                .build();
        this.createBulkhead = Bulkhead.of("psp-create", createBhConfig);

        BulkheadConfig statusBhConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(bhProps.getStatus().getMaxConcurrentCalls())
                .maxWaitDuration(bhProps.getStatus().getMaxWaitDuration())
                .build();
        this.statusBulkhead = Bulkhead.of("psp-status", statusBhConfig);

        // 3. Retry Configurations
        ProviderResilienceProperties.Retry retryProps = props.getRetry();
        this.createRetry = buildRetry("psp-create-retry", retryProps.getCreate(), true);
        this.statusRetry = buildRetry("psp-status-retry", retryProps.getStatus(), false);
    }

    private static Retry buildRetry(String name, ProviderResilienceProperties.Retry.Policy policy, boolean isCreate) {
        IntervalFunction intervalFn = IntervalFunction.ofExponentialRandomBackoff(
                policy.getInitialBackoff(),
                policy.getMultiplier(),
                policy.getJitterFactor()
        );

        IntervalFunction cappedIntervalFn = attempt -> {
            long computed = intervalFn.apply(attempt);
            long maxMs = policy.getMaxBackoff().toMillis();
            return Math.min(computed, maxMs);
        };

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(policy.getMaxAttempts())
                .intervalFunction(cappedIntervalFn)
                .retryOnException(ex -> isCreate ? isRetryableCreateException(ex) : isRetryableStatusException(ex))
                .build();

        return Retry.of(name, config);
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public Bulkhead getCreateBulkhead() {
        return createBulkhead;
    }

    public Bulkhead getStatusBulkhead() {
        return statusBulkhead;
    }

    public Retry getCreateRetry() {
        return createRetry;
    }

    public Retry getStatusRetry() {
        return statusRetry;
    }

    /**
     * Sends an operation request to the external PSP.
     * Decorated: CircuitBreaker -> Bulkhead -> AggregateLogical -> Retry -> Raw HTTP
     */
    public PspOperationResponse createOperation(
            UUID clientOperationId,
            String operationType,
            String amountMinor,
            String currency
    ) {
        CreateAttemptContext context = new CreateAttemptContext();

        Supplier<PspOperationResponse> rawHttpSupplier = () -> {
            context.recordAttempt();
            try {
                return executeRawCreate(clientOperationId, operationType, amountMinor, currency);
            } catch (Exception ex) {
                if (isDefinitePreAcceptanceFailure(ex)) {
                    context.recordDefiniteFailure(ex);
                } else {
                    context.recordAmbiguous(ex);
                }
                throw ex;
            }
        };

        Supplier<PspOperationResponse> retryDecorated = Retry.decorateSupplier(createRetry, rawHttpSupplier);

        Supplier<PspOperationResponse> logicalAggregateSupplier = () -> {
            try {
                return retryDecorated.get();
            } catch (Exception ex) {
                if (context.isAmbiguousAttemptSeen()) {
                    log.warn("CREATE for {} failed after ambiguous attempt seen (attempts={}): {}",
                            clientOperationId, context.getPhysicalAttempts(), ex.getMessage());
                    throw new PspAmbiguousOutcomeException(
                            "Logical provider CREATE outcome is ambiguous after " + context.getPhysicalAttempts()
                                    + " attempts (earlier ambiguous attempt seen): " + ex.getMessage(),
                            ex,
                            context.getPhysicalAttempts()
                    );
                }
                throw ex;
            }
        };

        Supplier<PspOperationResponse> bulkheadDecorated = Bulkhead.decorateSupplier(createBulkhead, logicalAggregateSupplier);
        Supplier<PspOperationResponse> circuitDecorated = CircuitBreaker.decorateSupplier(circuitBreaker, bulkheadDecorated);

        try {
            return circuitDecorated.get();
        } catch (CallNotPermittedException ex) {
            log.warn("PSP CREATE rejected: circuit breaker OPEN for clientOperationId {}", clientOperationId);
            throw new PspCallRejectedException(PspCallRejectedException.Reason.CIRCUIT_OPEN,
                    "Provider client call rejected: circuit breaker is OPEN", ex);
        } catch (BulkheadFullException ex) {
            log.warn("PSP CREATE rejected: bulkhead FULL for clientOperationId {}", clientOperationId);
            throw new PspCallRejectedException(PspCallRejectedException.Reason.BULKHEAD_FULL,
                    "Provider client call rejected: psp-create bulkhead is FULL", ex);
        }
    }

    private PspOperationResponse executeRawCreate(
            UUID clientOperationId,
            String operationType,
            String amountMinor,
            String currency
    ) {
        PspCreateOperationRequest request = new PspCreateOperationRequest(
                clientOperationId,
                operationType,
                amountMinor,
                currency,
                webhookUrl
        );

        log.info("Sending raw PSP operation request: clientOperationId={}, type={}, amount={}, currency={}",
                clientOperationId, operationType, amountMinor, currency);

        try {
            ResponseEntity<PspOperationResponse> response = restClient.post()
                    .uri("/api/provider/operations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, resp) -> {
                        byte[] bodyBytes = resp.getBody().readAllBytes();
                        String errorType = extractProblemType(bodyBytes);
                        throw new PspProtocolException("PSP returned 5xx server error: " + resp.getStatusCode(), resp.getStatusCode().value(), errorType);
                    })
                    .onStatus(status -> status.is4xxClientError(), (req, resp) -> {
                        byte[] bodyBytes = resp.getBody().readAllBytes();
                        String errorType = extractProblemType(bodyBytes);
                        throw new PspProtocolException("PSP returned 4xx client error: " + resp.getStatusCode(), resp.getStatusCode().value(), errorType);
                    })
                    .toEntity(PspOperationResponse.class);

            if (response.getBody() == null) {
                throw new PspProtocolException("PSP response body was null for status " + response.getStatusCode().value(), response.getStatusCode().value());
            }
            return response.getBody();
        } catch (ResourceAccessException ex) {
            log.warn("PSP transport error for clientOperationId {}: {}", clientOperationId, ex.getMessage());
            throw new PspTransportException("Transport error during PSP request: " + ex.getMessage(), ex);
        } catch (HttpMessageConversionException ex) {
            log.warn("PSP decoding error for clientOperationId {}: {}", clientOperationId, ex.getMessage());
            throw new PspProtocolException("Failed to decode PSP response body: " + ex.getMessage(), ex, 200);
        } catch (RestClientResponseException ex) {
            log.warn("PSP HTTP error for clientOperationId {}: status={}", clientOperationId, ex.getStatusCode());
            String errorType = extractProblemType(ex.getResponseBodyAsByteArray());
            throw new PspProtocolException("PSP error response: " + ex.getStatusCode(), ex.getStatusCode().value(), errorType);
        } catch (RestClientException ex) {
            log.warn("PSP client/transport error for clientOperationId {}: {}", clientOperationId, ex.getMessage());
            throw new PspTransportException("Transport or extraction error during PSP request: " + ex.getMessage(), ex);
        }
    }

    /**
     * Looks up an external operation status from the provider by clientOperationId.
     * Decorated: CircuitBreaker -> Bulkhead -> Retry -> Raw HTTP
     */
    public Optional<PspOperationResponse> getOperationByClientOperationId(UUID clientOperationId) {
        Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");

        Supplier<Optional<PspOperationResponse>> rawHttpSupplier = () -> executeRawGet(clientOperationId);
        Supplier<Optional<PspOperationResponse>> retryDecorated = Retry.decorateSupplier(statusRetry, rawHttpSupplier);
        Supplier<Optional<PspOperationResponse>> bulkheadDecorated = Bulkhead.decorateSupplier(statusBulkhead, retryDecorated);
        Supplier<Optional<PspOperationResponse>> circuitDecorated = CircuitBreaker.decorateSupplier(circuitBreaker, bulkheadDecorated);

        try {
            return circuitDecorated.get();
        } catch (CallNotPermittedException ex) {
            log.warn("PSP GET rejected: circuit breaker OPEN for clientOperationId {}", clientOperationId);
            throw new PspCallRejectedException(PspCallRejectedException.Reason.CIRCUIT_OPEN,
                    "Provider status call rejected: circuit breaker is OPEN", ex);
        } catch (BulkheadFullException ex) {
            log.warn("PSP GET rejected: bulkhead FULL for clientOperationId {}", clientOperationId);
            throw new PspCallRejectedException(PspCallRejectedException.Reason.BULKHEAD_FULL,
                    "Provider status call rejected: psp-status bulkhead is FULL", ex);
        }
    }

    private Optional<PspOperationResponse> executeRawGet(UUID clientOperationId) {
        log.debug("Executing raw PSP GET for clientOperationId: {}", clientOperationId);
        try {
            ResponseEntity<PspOperationResponse> response = restClient.get()
                    .uri("/api/provider/operations/by-client/{clientOperationId}", clientOperationId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, resp) -> {
                        // 404 handled gracefully
                    })
                    .onStatus(status -> status.is5xxServerError(), (req, resp) -> {
                        byte[] bodyBytes = resp.getBody().readAllBytes();
                        String errorType = extractProblemType(bodyBytes);
                        throw new PspProtocolException("PSP GET returned 5xx: " + resp.getStatusCode(), resp.getStatusCode().value(), errorType);
                    })
                    .onStatus(status -> status.is4xxClientError() && status.value() != 404, (req, resp) -> {
                        byte[] bodyBytes = resp.getBody().readAllBytes();
                        String errorType = extractProblemType(bodyBytes);
                        throw new PspProtocolException("PSP GET returned 4xx: " + resp.getStatusCode(), resp.getStatusCode().value(), errorType);
                    })
                    .toEntity(PspOperationResponse.class);

            if (response.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            if (response.getBody() == null) {
                throw new PspProtocolException("PSP GET response body was null for status " + response.getStatusCode().value(), response.getStatusCode().value());
            }
            return Optional.of(response.getBody());
        } catch (ResourceAccessException ex) {
            log.warn("PSP transport error during GET for clientOperationId {}: {}", clientOperationId, ex.getMessage());
            throw new PspTransportException("Transport error during PSP GET: " + ex.getMessage(), ex);
        } catch (HttpMessageConversionException ex) {
            log.warn("PSP decoding error during GET for clientOperationId {}: {}", clientOperationId, ex.getMessage());
            throw new PspProtocolException("Failed to decode PSP GET response body: " + ex.getMessage(), ex, 200);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            String errorType = extractProblemType(ex.getResponseBodyAsByteArray());
            throw new PspProtocolException("PSP GET error response: " + ex.getStatusCode(), ex, ex.getStatusCode().value(), errorType);
        } catch (RestClientException ex) {
            log.warn("PSP client/transport error during GET for clientOperationId {}: {}", clientOperationId, ex.getMessage());
            throw new PspTransportException("Transport or extraction error during PSP GET: " + ex.getMessage(), ex);
        }
    }

    private static boolean isRetryableCreateException(Throwable ex) {
        if (ex instanceof PspTransportException) {
            return true;
        }
        if (ex instanceof PspProtocolException protoEx) {
            Integer status = protoEx.getStatusCode();
            if (status != null) {
                if (status == 408 || status == 429) {
                    return true;
                }
                if (status >= 500 && status <= 599) {
                    return true;
                }
                if (status == 200) {
                    // Decoding or null body on 2xx
                    return true;
                }
            } else {
                // Null body or decoding exception without explicit status
                return true;
            }
        }
        return false;
    }

    private static boolean isRetryableStatusException(Throwable ex) {
        if (ex instanceof PspTransportException) {
            return true;
        }
        if (ex instanceof PspProtocolException protoEx) {
            Integer status = protoEx.getStatusCode();
            if (status != null) {
                if (status == 404) {
                    return false;
                }
                if (status == 408 || status == 429) {
                    return true;
                }
                if (status >= 500 && status <= 599) {
                    return true;
                }
                if (status == 200) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private static boolean isDefinitePreAcceptanceFailure(Throwable ex) {
        if (ex instanceof PspProtocolException protoEx) {
            Integer status = protoEx.getStatusCode();
            if (status != null && status == 500 && "urn:ledgerguard:psp:error:temporary-failure".equals(protoEx.getProviderErrorType())) {
                return true;
            }
            if (status != null && (status == 400 || status == 401 || status == 403 || status == 409 || status == 422)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCircuitBreakerFailure(Throwable ex) {
        if (ex instanceof PspTransportException || ex instanceof PspAmbiguousOutcomeException) {
            return true;
        }
        if (ex instanceof PspProtocolException protoEx) {
            Integer status = protoEx.getStatusCode();
            if (status != null) {
                if (status == 408 || status == 429) {
                    return true;
                }
                if (status >= 500 && status <= 599) {
                    return true;
                }
                if (status == 200) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private static boolean isCircuitBreakerIgnored(Throwable ex) {
        if (ex instanceof BulkheadFullException || ex instanceof CallNotPermittedException || ex instanceof PspCallRejectedException) {
            return true;
        }
        if (ex instanceof PspProtocolException protoEx) {
            Integer status = protoEx.getStatusCode();
            if (status != null) {
                if (status == 400 || status == 401 || status == 403 || status == 409 || status == 422) {
                    return true;
                }
            }
        }
        return false;
    }

    private String extractProblemType(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            if (root != null && root.has("type") && root.get("type").isTextual()) {
                return root.get("type").asText();
            }
        } catch (Exception e) {
            log.debug("Could not parse problem detail type from response: {}", e.getMessage());
        }
        return null;
    }
}
