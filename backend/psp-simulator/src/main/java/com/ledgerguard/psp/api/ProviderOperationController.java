package com.ledgerguard.psp.api;

import com.ledgerguard.psp.application.InvalidOperationException;
import com.ledgerguard.psp.application.ProviderOperationService;
import com.ledgerguard.psp.domain.OperationType;
import com.ledgerguard.psp.domain.ProviderOperation;
import com.ledgerguard.psp.domain.SimulatorScenario;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/provider/operations")
public class ProviderOperationController {

    private final ProviderOperationService operationService;

    public ProviderOperationController(ProviderOperationService operationService) {
        this.operationService = operationService;
    }

    @PostMapping
    public ResponseEntity<OperationResponse> createOperation(@Valid @RequestBody CreateOperationRequest request) {
        // 1. Validate operationType
        OperationType operationType;
        try {
            operationType = OperationType.valueOf(request.operationType().trim().toUpperCase());
        } catch (Exception e) {
            throw new InvalidOperationException("Invalid operationType: " + request.operationType());
        }

        // 2. Validate amountMinor exact integer representation
        long amountMinor;
        try {
            BigInteger bigInt = new BigInteger(request.amountMinor().trim());
            if (bigInt.signum() <= 0) {
                throw new InvalidOperationException("amountMinor must be strictly positive");
            }
            if (bigInt.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new InvalidOperationException("amountMinor exceeds maximum allowed signed 64-bit integer");
            }
            amountMinor = bigInt.longValueExact();
        } catch (NumberFormatException e) {
            throw new InvalidOperationException("amountMinor must be a valid integer string: " + request.amountMinor());
        }

        // 3. Validate currency
        if (!"INR".equalsIgnoreCase(request.currency().trim())) {
            throw new InvalidOperationException("Unsupported currency: " + request.currency() + ". Only INR is supported.");
        }

        // 4. Validate webhookUrl if provided
        String webhookUrl = request.webhookUrl();
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            try {
                URI uri = URI.create(webhookUrl.trim());
                String scheme = uri.getScheme();
                if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                    throw new InvalidOperationException("webhookUrl scheme must be http or https");
                }
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new InvalidOperationException("webhookUrl must have a valid host");
                }
                webhookUrl = uri.toString();
            } catch (Exception e) {
                if (e instanceof InvalidOperationException ioe) {
                    throw ioe;
                }
                throw new InvalidOperationException("Invalid webhookUrl URI: " + webhookUrl);
            }
        } else {
            webhookUrl = null;
        }

        // 5. Execute transactional operation
        ProviderOperationService.OperationExecutionResult result = operationService.executeOperation(
                request.clientOperationId(),
                operationType,
                amountMinor,
                "INR",
                webhookUrl
        );

        // 6. If TIMEOUT_AFTER_SUCCESS on fresh operation, delay response AFTER transaction commit
        if (result.scenario() == SimulatorScenario.TIMEOUT_AFTER_SUCCESS && !result.isReplay() && result.delayMs() > 0) {
            try {
                Thread.sleep(result.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        HttpStatus status = result.isReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(OperationResponse.from(result.operation()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OperationResponse> getOperationById(@PathVariable("id") UUID id) {
        ProviderOperation operation = operationService.getById(id);
        return ResponseEntity.ok(OperationResponse.from(operation));
    }

    @GetMapping("/by-client/{clientOperationId}")
    public ResponseEntity<OperationResponse> getOperationByClientOperationId(
            @PathVariable("clientOperationId") UUID clientOperationId
    ) {
        ProviderOperation operation = operationService.getByClientOperationId(clientOperationId);
        return ResponseEntity.ok(OperationResponse.from(operation));
    }
}
