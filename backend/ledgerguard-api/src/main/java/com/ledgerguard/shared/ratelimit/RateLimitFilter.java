package com.ledgerguard.shared.ratelimit;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Servlet filter for token-bucket rate limiting and admission control.
 * Positioned in the Spring Security filter chain immediately after AuthorizationFilter.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout"
    );

    private static final Pattern REFUND_PATH_PATTERN = Pattern.compile("^/api/payments/[^/]+/refund$");

    private final RateLimitService rateLimitService;
    private final RateLimitResponseWriter responseWriter;

    public RateLimitFilter(RateLimitService rateLimitService, RateLimitResponseWriter responseWriter) {
        this.rateLimitService = Objects.requireNonNull(rateLimitService, "rateLimitService must not be null");
        this.responseWriter = Objects.requireNonNull(responseWriter, "responseWriter must not be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        RateLimitPolicy policy = classifyPolicy(request);

        if (policy == RateLimitPolicy.EXEMPT) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request, policy);
        ConsumptionProbe probe = rateLimitService.tryConsume(key, policy);

        if (probe == null || probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long nanosToWait = probe.getNanosToWaitForRefill();
        long retryAfterSeconds = Math.max(1L, (nanosToWait + 999_999_999L) / 1_000_000_000L);

        log.debug("Rate limit exceeded for key '{}' under policy '{}'. Retry-After: {}s",
                key, policy, retryAfterSeconds);

        responseWriter.writeRateLimitResponse(request, response, retryAfterSeconds);
    }

    private RateLimitPolicy classifyPolicy(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        // 1. CORS Preflight
        if (HttpMethod.OPTIONS.matches(method)) {
            return RateLimitPolicy.EXEMPT;
        }

        // 2. Health and Info Probes
        if (uri.equals("/actuator/health") || uri.startsWith("/actuator/health/") || uri.equals("/actuator/info")) {
            return RateLimitPolicy.EXEMPT;
        }

        // 3. PSP Webhook Ingress (Exempt in Phase 27 to avoid permanent provider webhook failure)
        if (HttpMethod.POST.matches(method) && uri.equals("/api/provider/webhooks")) {
            return RateLimitPolicy.EXEMPT;
        }

        // 4. Public Authentication Endpoints
        if (HttpMethod.POST.matches(method) && PUBLIC_AUTH_PATHS.contains(uri)) {
            return RateLimitPolicy.PUBLIC_AUTH;
        }

        // 5. Operations & Reconciliation Endpoints
        if (uri.startsWith("/api/reconciliation") || uri.startsWith("/api/ops")) {
            return RateLimitPolicy.OPS;
        }

        // 6. Financial Write Mutations
        if (HttpMethod.POST.matches(method) && isFinancialWritePath(uri)) {
            return RateLimitPolicy.FINANCIAL_WRITE;
        }

        // 7. Remaining API Endpoints
        return RateLimitPolicy.AUTHENTICATED_GENERAL;
    }

    private boolean isFinancialWritePath(String uri) {
        return uri.equals("/api/transfers")
                || uri.equals("/api/payments")
                || uri.equals("/api/funding")
                || uri.equals("/api/payouts")
                || REFUND_PATH_PATTERN.matcher(uri).matches();
    }

    private String resolveKey(HttpServletRequest request, RateLimitPolicy policy) {
        // For PUBLIC_AUTH: ALWAYS key by remote IP address, even if an Authorization token is supplied
        if (policy == RateLimitPolicy.PUBLIC_AUTH) {
            return "PUBLIC_AUTH:ip:" + resolveRemoteAddress(request);
        }

        // For protected endpoints: Key by authenticated JWT subject UUID
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            String subject = auth.getName();
            return policy.name() + ":user:" + subject;
        }

        // Fallback for unauthenticated calls reaching this point
        return policy.name() + ":ip:" + resolveRemoteAddress(request);
    }

    private String resolveRemoteAddress(HttpServletRequest request) {
        // Direct container remote address; X-Forwarded-For is not trusted by default
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null ? remoteAddr : "unknown";
    }
}
