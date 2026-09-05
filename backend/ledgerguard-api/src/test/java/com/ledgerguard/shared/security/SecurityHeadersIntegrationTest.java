package com.ledgerguard.shared.security;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "ledgerguard.security.cors.allowed-origins=https://app.example.test"
})
@DisplayName("Phase 28 â€” HTTP Security Headers & CORS Verification Tests")
class SecurityHeadersIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("API response contains explicit Content-Security-Policy")
    void apiResponseContainsExplicitCsp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
    }

    @Test
    @DisplayName("API response contains X-Content-Type-Options: nosniff and X-Frame-Options: DENY")
    void apiResponseContainsFrameAndContentTypeHeaders() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("Secure HTTPS request emits Strict-Transport-Security header")
    void secureRequestEmitsHsts() throws Exception {
        mockMvc.perform(get("/actuator/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    }

    @Test
    @DisplayName("Insecure HTTP request does NOT emit Strict-Transport-Security header")
    void insecureRequestDoesNotEmitHsts() throws Exception {
        mockMvc.perform(get("/actuator/health").secure(false))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @DisplayName("CORS preflight from configured origin (https://app.example.test) receives Access-Control-Allow-Origin, credentials, and exposed Retry-After")
    void corsConfiguredOriginPreflight() throws Exception {
        mockMvc.perform(options("/api/transfers")
                        .header("Origin", "https://app.example.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type,Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.test"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"));
    }

    @Test
    @DisplayName("CORS from untrusted origin (https://evil.example.test) receives NO Access-Control-Allow-Origin")
    void corsUntrustedOriginRejected() throws Exception {
        mockMvc.perform(options("/api/transfers")
                        .header("Origin", "https://evil.example.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("CORS from configured origin does NOT emit wildcard Access-Control-Allow-Origin when credentials are true")
    void corsNeverEmitsWildcardWithCredentials() throws Exception {
        mockMvc.perform(options("/api/transfers")
                        .header("Origin", "https://app.example.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Origin", org.hamcrest.Matchers.not("*")));
    }
}