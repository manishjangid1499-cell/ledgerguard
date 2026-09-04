package com.ledgerguard.shared.ratelimit;

import com.ledgerguard.shared.error.ApiErrorCode;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RateLimitFilterUnitTest {

    private RateLimitService rateLimitService;
    private RateLimitResponseWriter responseWriter;
    private RateLimitFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        responseWriter = new RateLimitResponseWriter(new ObjectMapper());
        filter = new RateLimitFilter(rateLimitService, responseWriter);
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("OPTIONS preflight requests are EXEMPT and bypass rate limiting")
    void testOptionsExempt() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/transfers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService, never()).tryConsume(any(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Actuator health and info endpoints are EXEMPT")
    void testActuatorExempt() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService, never()).tryConsume(any(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("PSP Webhook POST is EXEMPT to prevent permanent delivery drops")
    void testProviderWebhookExempt() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/provider/webhooks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService, never()).tryConsume(any(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("PUBLIC_AUTH is always keyed by remote IP even if Authorization token is provided")
    void testPublicAuthRemoteIpKeying() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Authorization", "Bearer some-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Even if security context had an auth, policy-first classification forces IP
        Authentication auth = new UsernamePasswordAuthenticationToken("user-123", "n/a");
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(rateLimitService.tryConsume("PUBLIC_AUTH:ip:10.0.0.5", RateLimitPolicy.PUBLIC_AUTH))
                .thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).tryConsume("PUBLIC_AUTH:ip:10.0.0.5", RateLimitPolicy.PUBLIC_AUTH);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("FINANCIAL_WRITE routes with JWT are keyed by JWT subject UUID")
    void testFinancialWriteKeying() throws ServletException, IOException {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                userId.toString(),
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(rateLimitService.tryConsume("FINANCIAL_WRITE:user:" + userId, RateLimitPolicy.FINANCIAL_WRITE))
                .thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).tryConsume("FINANCIAL_WRITE:user:" + userId, RateLimitPolicy.FINANCIAL_WRITE);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Rejection writes HTTP 429, Retry-After header, and halts chain")
    void testRejectionHaltChain() throws ServletException, IOException {
        UUID userId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                userId.toString(),
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ConsumptionProbe rejectedProbe = ConsumptionProbe.rejected(0L, 2_500_000_000L, 2_500_000_000L); // 2.5s -> 3s ceiling
        when(rateLimitService.tryConsume("FINANCIAL_WRITE:user:" + userId, RateLimitPolicy.FINANCIAL_WRITE))
                .thenReturn(rejectedProbe);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains(ApiErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(response.getContentAsString()).contains("urn:ledgerguard:error:rate-limit-exceeded");
    }
}
