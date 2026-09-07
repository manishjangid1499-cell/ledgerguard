package com.ledgerguard.e2e.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public class E2EHttpClient {

    private final String apiBaseUrl;
    private final String pspBaseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private String activeBearerToken;

    public E2EHttpClient(String apiBaseUrl, String pspBaseUrl) {
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        this.pspBaseUrl = pspBaseUrl.replaceAll("/+$", "");
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void setBearerToken(String token) {
        this.activeBearerToken = token;
    }

    public void clearBearerToken() {
        this.activeBearerToken = null;
    }

    public record HttpResponseView(int statusCode, String body, JsonNode json) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public HttpResponseView register(String email, String password, String role) {
        Map<String, Object> payload = role != null ?
                Map.of("email", email, "password", password, "role", role) :
                Map.of("email", email, "password", password);
        return postApi("/api/auth/register", payload, null, false);
    }

    public HttpResponseView login(String email, String password) {
        HttpResponseView res = postApi("/api/auth/login", Map.of("email", email, "password", password), null, false);
        if (res.isSuccess() && res.json().has("accessToken")) {
            this.activeBearerToken = res.json().get("accessToken").asText();
        }
        return res;
    }

    public HttpResponseView getApi(String path) {
        return getApi(path, true);
    }

    public HttpResponseView getApi(String path, boolean sendAuth) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + path))
                .GET()
                .timeout(Duration.ofSeconds(10));

        if (sendAuth && activeBearerToken != null) {
            builder.header("Authorization", "Bearer " + activeBearerToken);
        }

        return execute(builder.build());
    }

    public HttpResponseView postApi(String path, Object body, UUID idempotencyKey) {
        return postApi(path, body, idempotencyKey, true);
    }

    public HttpResponseView postApi(String path, Object body, UUID idempotencyKey, boolean sendAuth) {
        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10));

        if (sendAuth && activeBearerToken != null) {
            builder.header("Authorization", "Bearer " + activeBearerToken);
        }

        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey.toString());
        }

        return execute(builder.build());
    }

    public HttpResponseView putPspScenario(UUID clientOperationId, String scenario, Long delayMs, Integer temporaryFailureCount) {
        Map<String, Object> payload = Map.of(
                "scenario", scenario,
                "delayMs", delayMs != null ? delayMs : 0L,
                "temporaryFailureCount", temporaryFailureCount != null ? temporaryFailureCount : 0
        );

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pspBaseUrl + "/api/simulator/scenarios/" + clientOperationId))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        return execute(request);
    }

    public HttpResponseView getPsp(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pspBaseUrl + path))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        return execute(request);
    }

    public HttpResponseView putPsp(String path, Object body) {
        String jsonBody = "";
        if (body != null) {
            try {
                jsonBody = mapper.writeValueAsString(body);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize request body", e);
            }
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pspBaseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();
        return execute(request);
    }

    public HttpResponseView postPsp(String path, Map<String, Object> body) {
        String jsonBody = "";
        if (body != null) {
            try {
                jsonBody = mapper.writeValueAsString(body);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize request body", e);
            }
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(pspBaseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();
        return execute(request);
    }

    private HttpResponseView execute(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = null;
            if (response.body() != null && !response.body().isBlank()) {
                try {
                    node = mapper.readTree(response.body());
                } catch (Exception ignored) {
                }
            }
            return new HttpResponseView(response.statusCode(), response.body(), node);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP request failed: " + request.method() + " " + request.uri(), e);
        }
    }
}
