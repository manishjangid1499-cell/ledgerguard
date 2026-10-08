package com.featureflag.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class FeatureFlagClient {

    public static final String SDK_KEY_HEADER =
            "X-Feature-Flag-Key";
    private static final String SDK_KEY_PREFIX = "ff_sdk_";
    private static final int SDK_KEY_SECRET_LENGTH = 43;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final String baseUrl;
    private final String sdkKey;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private FeatureFlagClient(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.sdkKey = requireSdkKey(builder.sdkKey);
        this.requestTimeout = positive(
                builder.requestTimeout,
                "requestTimeout"
        );
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(positive(
                        builder.connectTimeout,
                        "connectTimeout"
                ))
                .build();
        this.objectMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEnabled(
            String flagKey,
            String subject,
            boolean defaultValue
    ) {
        String requiredFlagKey = requireValue(flagKey, "flagKey");
        String requiredSubject = requireValue(subject, "subject");
        URI uri = URI.create(
                baseUrl
                        + "/runtime/v1/flags/"
                        + encode(requiredFlagKey)
                        + "/evaluate?subject="
                        + encode(requiredSubject)
        );
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header(SDK_KEY_HEADER, sdkKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
            if (response.statusCode() != 200) {
                return defaultValue;
            }

            return readEnabled(
                    response.body(),
                    requiredFlagKey,
                    defaultValue
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return defaultValue;
        } catch (IOException | RuntimeException exception) {
            return defaultValue;
        }
    }

    private static String normalizeBaseUrl(String value) {
        String baseUrl = requireValue(value, "baseUrl");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "baseUrl must be a valid HTTP(S) URL",
                    exception
            );
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getPort() > 65_535
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must be a valid HTTP(S) URL without credentials, query, or fragment"
            );
        }

        String normalized = baseUrl;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            );
        }
        return normalized;
    }

    private static String requireSdkKey(String value) {
        String key = requireValue(value, "sdkKey");
        if (!key.startsWith(SDK_KEY_PREFIX)
                || key.length()
                != SDK_KEY_PREFIX.length() + SDK_KEY_SECRET_LENGTH) {
            throw invalidSdkKey();
        }

        for (int index = SDK_KEY_PREFIX.length();
             index < key.length();
             index++) {
            if (!isBase64UrlCharacter(key.charAt(index))) {
                throw invalidSdkKey();
            }
        }
        return key;
    }

    private static boolean isBase64UrlCharacter(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '_';
    }

    private static IllegalArgumentException invalidSdkKey() {
        return new IllegalArgumentException(
                "sdkKey has an invalid format"
        );
    }

    private static String requireValue(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }
        return value.trim();
    }

    private static Duration positive(
            Duration duration,
            String field
    ) {
        Objects.requireNonNull(duration, field + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    field + " must be positive"
            );
        }
        return duration;
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte current : bytes) {
            int unsigned = Byte.toUnsignedInt(current);
            if (isUnreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%')
                        .append(HEX[unsigned >>> 4])
                        .append(HEX[unsigned & 0x0F]);
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    private boolean readEnabled(
            String body,
            String requestedFlagKey,
            boolean defaultValue
    ) throws IOException {
        JsonNode response = objectMapper.readTree(body);
        if (response == null || !response.isObject()) {
            return defaultValue;
        }

        JsonNode enabled = response.get("enabled");
        if (enabled == null || !enabled.isBoolean()) {
            return defaultValue;
        }

        JsonNode returnedFlagKey = response.get("flagKey");
        if (returnedFlagKey != null
                && (!returnedFlagKey.isTextual()
                || !requestedFlagKey.equalsIgnoreCase(returnedFlagKey.textValue()))) {
            return defaultValue;
        }

        JsonNode environment = response.get("environment");
        if (environment != null
                && (!environment.isTextual()
                || environment.textValue().isBlank())) {
            return defaultValue;
        }
        return enabled.booleanValue();
    }

    public static final class Builder {

        private String baseUrl;
        private String sdkKey;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(3);

        private Builder() {
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder sdkKey(String sdkKey) {
            this.sdkKey = sdkKey;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public FeatureFlagClient build() {
            return new FeatureFlagClient(this);
        }
    }
}
