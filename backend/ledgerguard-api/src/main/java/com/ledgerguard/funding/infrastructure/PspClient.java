package com.ledgerguard.funding.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring RestClient wrapper for outbound HTTP integration with the external PSP simulator.
 * <p>
 * Enforces bounded connect and read timeouts, and translates HTTP / transport outcomes into
 * controlled internal exception types without holding database connections or transactions.
 */
@Component
public class PspClient {

    private static final Logger log = LoggerFactory.getLogger(PspClient.class);

    private final RestClient restClient;
    private final String webhookUrl;
    private final ObjectMapper objectMapper;

    public PspClient(
            @Value("${ledgerguard.psp.base-url:http://localhost:8081}") String baseUrl,
            @Value("${ledgerguard.psp.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ledgerguard.psp.read-timeout-ms:2000}") int readTimeoutMs,
            @Value("${ledgerguard.psp.webhook-url:http://localhost:8080/api/provider/webhooks}") String webhookUrl,
            ObjectMapper objectMapper
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
    }

    /**
     * Sends an operation request to the external PSP.
     *
     * @param clientOperationId stable correlation ID (FundingOperation.id or Payout.id)
     * @param operationType operation type (CREDIT or DEBIT)
     * @param amountMinor exact decimal string amount
     * @param currency currency code (INR)
     * @return deserialized provider operation response
     * @throws PspTransportException on network timeout, I/O error, or connection refusal
     * @throws PspProtocolException on HTTP 4xx, 5xx, or malformed response
     */
    public PspOperationResponse createOperation(
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

        log.info("Sending PSP operation request: clientOperationId={}, type={}, amount={}, currency={}",
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
                throw new PspProtocolException("PSP response body was null");
            }
            return response.getBody();
        } catch (ResourceAccessException ex) {
            log.warn("PSP transport error for clientOperationId {}: {}", clientOperationId, ex.getMessage());
            throw new PspTransportException("Transport error during PSP request: " + ex.getMessage(), ex);
        } catch (RestClientResponseException ex) {
            log.warn("PSP HTTP error for clientOperationId {}: status={}", clientOperationId, ex.getStatusCode());
            String errorType = extractProblemType(ex.getResponseBodyAsByteArray());
            throw new PspProtocolException("PSP error response: " + ex.getStatusCode(), ex.getStatusCode().value(), errorType);
        }
    }

    /**
     * Looks up an external operation status from the provider by clientOperationId.
     *
     * @param clientOperationId client correlation ID
     * @return Optional containing PspOperationResponse if found, or empty if 404
     */
    public Optional<PspOperationResponse> getOperationByClientOperationId(UUID clientOperationId) {
        Objects.requireNonNull(clientOperationId, "clientOperationId must not be null");
        log.debug("Querying PSP operation by clientOperationId: {}", clientOperationId);
        try {
            ResponseEntity<PspOperationResponse> response = restClient.get()
                    .uri("/api/provider/operations/by-client/{clientOperationId}", clientOperationId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, resp) -> {
                        // 404 is handled gracefully by returning empty Optional
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

            if (response.getStatusCode().value() == 404 || response.getBody() == null) {
                return Optional.empty();
            }
            return Optional.of(response.getBody());
        } catch (ResourceAccessException ex) {
            log.warn("PSP transport error during GET for clientOperationId {}: {}", clientOperationId, ex.getMessage());
            throw new PspTransportException("Transport error during PSP GET: " + ex.getMessage(), ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            String errorType = extractProblemType(ex.getResponseBodyAsByteArray());
            throw new PspProtocolException("PSP GET error response: " + ex.getStatusCode(), ex.getStatusCode().value(), errorType);
        }
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
