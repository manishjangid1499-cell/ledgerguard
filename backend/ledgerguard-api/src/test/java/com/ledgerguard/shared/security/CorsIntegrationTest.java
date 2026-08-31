package com.ledgerguard.shared.security;

import com.ledgerguard.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorsIntegrationTest {

    @Nested
    @DisplayName("CORS with configured allowed origin")
    @TestPropertySource(properties = "ledgerguard.security.cors.allowed-origins=http://localhost:5173")
    class ConfiguredAllowedOriginTest extends AbstractIntegrationTest {

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
        @DisplayName("Preflight OPTIONS request from configured origin receives CORS headers with credentials")
        void preflightFromConfiguredOriginSucceeds() throws Exception {
            mockMvc.perform(options("/api/auth/login")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,Authorization"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, not("*")))
                    .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
        }

        @Test
        @DisplayName("Request from configured origin receives Access-Control-Allow-Origin and Credentials")
        void requestFromConfiguredOriginReceivesCorsHeaders() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, not("*")));
        }

        @Test
        @DisplayName("Request from disallowed origin does not receive Access-Control-Allow-Origin header")
        void requestFromDisallowedOriginDoesNotReceiveCorsHeader() throws Exception {
            mockMvc.perform(options("/api/auth/login")
                            .header(HttpHeaders.ORIGIN, "https://example.invalid")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @DisplayName("CORS with no allowed origins configured")
    @TestPropertySource(properties = "ledgerguard.security.cors.allowed-origins=")
    class NoAllowedOriginsConfiguredTest extends AbstractIntegrationTest {

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
        @DisplayName("When no origins are configured, localhost is not allowed")
        void noOriginsConfiguredRejectsLocalhost() throws Exception {
            mockMvc.perform(options("/api/auth/login")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        @DisplayName("When no origins are configured, arbitrary origins are not allowed")
        void noOriginsConfiguredRejectsArbitraryOrigin() throws Exception {
            mockMvc.perform(options("/api/auth/login")
                            .header(HttpHeaders.ORIGIN, "https://example.invalid")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }
}
