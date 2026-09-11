package com.ledgerguard.shared.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * OpenAPI 3.1 configuration for LedgerGuard financial platform.
 * Configures authoritative API metadata, security schemes, and per-endpoint
 * role authorization and authentication bindings.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    public static final String REFRESH_COOKIE_AUTH = "refreshCookie";
    public static final String PSP_WEBHOOK_SIGNATURE = "pspWebhookSignature";
    public static final String PSP_WEBHOOK_TIMESTAMP = "pspWebhookTimestamp";

    @Bean
    public OpenAPI ledgerGuardOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LedgerGuard Payment Integrity & Ledger Platform API")
                        .description("High-reliability financial core implementing immutable double-entry ledgering, " +
                                "deterministic concurrency control, transactional outbox messaging, multi-level " +
                                "reconciliation, and ambiguous external payment recovery.")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Stateless JWT access token. Required for authenticated user, customer, merchant, and ops endpoints."))
                        .addSecuritySchemes(REFRESH_COOKIE_AUTH, new SecurityScheme()
                                .name("ledgerguard_refresh_token")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .description("Opaque refresh token delivered via HttpOnly, SameSite=Strict cookie for session rotation and logout."))
                        .addSecuritySchemes(PSP_WEBHOOK_SIGNATURE, new SecurityScheme()
                                .name("X-PSP-Webhook-Signature")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .description("HMAC-SHA256 signature payload verification header for external PSP webhook events."))
                        .addSecuritySchemes(PSP_WEBHOOK_TIMESTAMP, new SecurityScheme()
                                .name("X-PSP-Webhook-Timestamp")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .description("Unix epoch millisecond timestamp header for replay attack protection.")));
    }

    @Bean
    public OpenApiCustomizer securityAndRoleCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) return;

            openApi.getPaths().forEach((path, pathItem) -> {
                bindOperationSecurity(path, "POST", pathItem.getPost());
                bindOperationSecurity(path, "GET", pathItem.getGet());
                bindOperationSecurity(path, "PUT", pathItem.getPut());
                bindOperationSecurity(path, "DELETE", pathItem.getDelete());
            });
        };
    }

    private void bindOperationSecurity(String path, String method, Operation operation) {
        if (operation == null) return;

        // 1. Public Authentication Endpoints
        if (path.equals("/api/auth/register") || path.equals("/api/auth/login")) {
            operation.setSecurity(Collections.emptyList());
            operation.addExtension("x-auth-type", "PUBLIC");
            operation.setDescription((operation.getDescription() != null ? operation.getDescription() + "\n\n" : "")
                    + "**Access**: Public (Unauthenticated). Permitted registration roles: `CUSTOMER`, `MERCHANT` (`OPS` forbidden).");
        }
        // 2. Refresh Cookie Endpoints
        else if (path.equals("/api/auth/refresh") || path.equals("/api/auth/logout")) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(REFRESH_COOKIE_AUTH)));
            operation.addExtension("x-auth-type", "REFRESH_COOKIE");
            operation.setDescription((operation.getDescription() != null ? operation.getDescription() + "\n\n" : "")
                    + "**Access**: Authenticated via `ledgerguard_refresh_token` HttpOnly cookie.");
        }
        // 3. Authenticated User Profile
        else if (path.equals("/api/auth/me")) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER_AUTH)));
            operation.addExtension("x-auth-type", "BEARER_JWT");
            operation.setDescription((operation.getDescription() != null ? operation.getDescription() + "\n\n" : "")
                    + "**Access**: Authenticated (Any valid JWT Bearer token).");
        }
        // 4. Customer / Merchant Endpoints
        else if (path.startsWith("/api/transfers") || path.equals("/api/wallets/me") || path.equals("/api/payouts")) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER_AUTH)));
            operation.addExtension("x-required-roles", List.of("CUSTOMER", "MERCHANT"));
            operation.setDescription((operation.getDescription() != null ? operation.getDescription() + "\n\n" : "")
                    + "**Required Roles**: `ROLE_CUSTOMER` or `ROLE_MERCHANT` (Bearer JWT). `OPS` is forbidden.");
        }
        // 5. Customer-Only Endpoints
        else if (path.equals("/api/payments") || path.equals("/api/funding")) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER_AUTH)));
            operation.addExtension("x-required-roles", List.of("CUSTOMER"));
            operation.setDescription((operation.getDescription() != null ? operation.getDescription() + "\n\n" : "")
                    + "**Required Roles**: `ROLE_CUSTOMER` (Bearer JWT). `MERCHANT` and `OPS` are forbidden.");
        }
        // 6. Merchant-Only Endpoints
        else if (path.equals("/api/payments/{paymentId}/refund")) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER_AUTH)));
            operation.addExtension("x-required-roles", List.of("MERCHANT"));
            operation.setDescription((operation.getDescription() != null ? operation.getDescription() + "\n\n" : "")
                    + "**Required Roles**: `ROLE_MERCHANT` (Bearer JWT). `CUSTOMER` and `OPS` are forbidden.");
        }
        // 7. Operations / Reconciliation Endpoints
        else if (path.startsWith("/api/reconciliation")) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER_AUTH)));
            operation.addExtension("x-required-roles", List.of("OPS"));
            operation.setDescription((operation.getDescription() != null ? operation.getDescription() + "\n\n" : "")
                    + "**Required Roles**: `ROLE_OPS` (Bearer JWT). Customers and Merchants are strictly forbidden.");
        }
        // 8. PSP Provider Webhook Ingress
        else if (path.equals("/api/provider/webhooks")) {
            operation.setSecurity(List.of(
                    new SecurityRequirement()
                            .addList(PSP_WEBHOOK_SIGNATURE)
                            .addList(PSP_WEBHOOK_TIMESTAMP)
            ));
            operation.addExtension("x-auth-type", "HMAC_SHA256");
            operation.setDescription((operation.getDescription() != null ? operation.getDescription() + "\n\n" : "")
                    + "**Access**: External PSP callback. Authenticated via `X-PSP-Webhook-Signature` (HMAC-SHA256) and `X-PSP-Webhook-Timestamp` headers.");
        }
    }
}
