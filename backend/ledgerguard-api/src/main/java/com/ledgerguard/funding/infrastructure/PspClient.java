package com.ledgerguard.funding.infrastructure;

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

    public PspClient(
            @Value("${ledgerguard.psp.base-url:http://localhost:8081}") String baseUrl,
            @Value("${ledgerguard.psp.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ledgerguard.psp.read-timeout-ms:2000}") int readTimeoutMs
    ) {
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
     * @param clientOperationId stable correlation ID (FundingOperation.id)
     * @param operationType operation type (CREDIT)
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
                null
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
                        throw new PspProtocolException("PSP returned 5xx server error: " + resp.getStatusCode(), resp.getStatusCode().value());
                    })
                    .onStatus(status -> status.is4xxClientError(), (req, resp) -> {
                        throw new PspProtocolException("PSP returned 4xx client error: " + resp.getStatusCode(), resp.getStatusCode().value());
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
            throw new PspProtocolException("PSP error response: " + ex.getStatusCode(), ex.getStatusCode().value());
        }
    }
}
