package com.ledgerguard.provider.api;

import com.ledgerguard.provider.application.ProviderWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/provider")
public class ProviderWebhookController {

    private final ProviderWebhookService webhookService;

    public ProviderWebhookController(ProviderWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhooks")
    public ResponseEntity<Map<String, String>> receiveWebhook(
            @RequestHeader(value = "X-PSP-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-PSP-Webhook-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] rawBody
    ) {
        ProviderWebhookService.WebhookStatusResult result =
                webhookService.handleWebhook(timestamp, signature, rawBody != null ? rawBody : new byte[0]);

        if (result == ProviderWebhookService.WebhookStatusResult.ACCEPTED) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "ACCEPTED"));
        }

        return ResponseEntity.ok(Map.of("status", "OK"));
    }
}
