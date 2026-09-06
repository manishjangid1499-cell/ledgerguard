package com.ledgerguard.lab.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight HTTP provider test adapter running on an ephemeral port.
 * <p>
 * Emulates the external banking/PSP provider over real HTTP/TCP sockets,
 * allowing LedgerGuard's production PspClient to execute against realistic
 * provider conditions (e.g. TIMEOUT_AFTER_SUCCESS where provider commits
 * but drops the network connection).
 * <p>
 * Contains ZERO LedgerGuard business logic.
 */
public class HttpProviderTestAdapter {

    public enum Mode {
        NORMAL,
        TIMEOUT_AFTER_SUCCESS
    }

    private final HttpServer server;
    private final int port;
    private volatile Mode mode = Mode.NORMAL;

    private final Map<UUID, ProviderOperation> operations = new ConcurrentHashMap<>();
    private final Map<UUID, ProviderOperation> operationsByClient = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger physicalCreateAttempts = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger physicalGetAttempts = new java.util.concurrent.atomic.AtomicInteger(0);

    private static final Pattern BY_CLIENT_PATTERN = Pattern.compile("/api/provider/operations/by-client/([a-f0-9\\-]+)");

    public HttpProviderTestAdapter() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.port = server.getAddress().getPort();

        server.createContext("/api/provider/operations", this::handleOperations);
        server.setExecutor(null);
        server.start();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public int getPort() {
        return port;
    }

    public void clear() {
        operations.clear();
        operationsByClient.clear();
        physicalCreateAttempts.set(0);
        physicalGetAttempts.set(0);
        this.mode = Mode.NORMAL;
    }

    public void stop() {
        server.stop(0);
    }

    public int getOperationCount() {
        return operations.size();
    }

    public int getPhysicalCreateAttempts() {
        return physicalCreateAttempts.get();
    }

    public int getPhysicalGetAttempts() {
        return physicalGetAttempts.get();
    }

    private void handleOperations(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String path = exchange.getRequestURI().getPath();

        if ("POST".equals(method) && "/api/provider/operations".equals(path)) {
            handleCreateOperation(exchange);
        } else if ("GET".equals(method) && path.startsWith("/api/provider/operations/by-client/")) {
            handleGetByClientOperationId(exchange, path);
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private void handleCreateOperation(HttpExchange exchange) throws IOException {
        physicalCreateAttempts.incrementAndGet();
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        // Parse clientOperationId, amount, currency, operationType
        UUID clientOpId = extractUuid(body, "clientOperationId");
        String amount = extractField(body, "amount");
        if (amount == null) {
            amount = extractField(body, "amountMinor");
        }
        if (amount == null) {
            amount = "10000";
        }
        String currency = extractField(body, "currency");
        if (currency == null) {
            currency = "INR";
        }
        String operationType = extractField(body, "operationType");
        if (operationType == null) {
            operationType = "DEBIT";
        }

        ProviderOperation op;
        if (clientOpId != null && operationsByClient.containsKey(clientOpId)) {
            op = operationsByClient.get(clientOpId);
        } else {
            UUID providerOpId = UUID.randomUUID();
            String nowIso = Instant.now().toString();
            op = new ProviderOperation(
                    providerOpId,
                    clientOpId,
                    operationType,
                    amount,
                    currency,
                    "SUCCEEDED",
                    nowIso,
                    nowIso,
                    false
            );
            operations.put(providerOpId, op);
            if (clientOpId != null) {
                operationsByClient.put(clientOpId, op);
            }
        }

        if (mode == Mode.TIMEOUT_AFTER_SUCCESS) {
            // TIMEOUT_AFTER_SUCCESS: Provider committed internally, but transport times out / drops connection!
            // Abruptly close the exchange without sending response headers to trigger transport failure
            exchange.close();
            return;
        }

        // Normal success: Return 201 Created
        String responseJson = String.format(
                "{\"providerOperationId\":\"%s\",\"clientOperationId\":\"%s\",\"operationType\":\"%s\",\"amountMinor\":\"%s\",\"currency\":\"%s\",\"status\":\"SUCCEEDED\",\"createdAt\":\"%s\",\"completedAt\":\"%s\",\"replayed\":false}",
                op.id, op.clientOperationId, op.operationType, op.amount, op.currency, op.createdAt, op.settledAt
        );

        byte[] respBytes = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, respBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(respBytes);
        }
    }

    private void handleGetByClientOperationId(HttpExchange exchange, String path) throws IOException {
        physicalGetAttempts.incrementAndGet();
        Matcher matcher = BY_CLIENT_PATTERN.matcher(path);
        if (!matcher.find()) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        UUID clientOpId = UUID.fromString(matcher.group(1));
        ProviderOperation op = operationsByClient.get(clientOpId);

        if (op == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        String responseJson = String.format(
                "{\"providerOperationId\":\"%s\",\"clientOperationId\":\"%s\",\"operationType\":\"%s\",\"amountMinor\":\"%s\",\"currency\":\"%s\",\"status\":\"%s\",\"createdAt\":\"%s\",\"completedAt\":\"%s\",\"replayed\":false}",
                op.id, op.clientOperationId, op.operationType, op.amount, op.currency, op.status, op.createdAt, op.settledAt
        );

        byte[] respBytes = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, respBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(respBytes);
        }
    }

    private UUID extractUuid(String json, String key) {
        String val = extractField(json, key);
        return val != null ? UUID.fromString(val) : null;
    }

    private String extractField(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^,\"}]+)\"?");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    public record ProviderOperation(
            UUID id,
            UUID clientOperationId,
            String operationType,
            String amount,
            String currency,
            String status,
            String createdAt,
            String settledAt,
            boolean replayed
    ) {}
}
