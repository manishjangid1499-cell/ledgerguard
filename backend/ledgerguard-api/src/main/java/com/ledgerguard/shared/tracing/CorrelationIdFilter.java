package com.ledgerguard.shared.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Diagnostic HTTP servlet filter that captures or generates an X-Correlation-Id header,
 * echoing it in the HTTP response and attaching it to the SLF4J MDC context.
 * <p>
 * Positioned before SecurityContextHolderFilter in the Spring Security filter chain.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID_KEY = "correlationId";
    private static final int MAX_CORRELATION_ID_LENGTH = 64;
    private static final Pattern VALID_CORRELATION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String rawHeader = request.getHeader(CORRELATION_ID_HEADER);
        String correlationId = sanitizeOrGenerate(rawHeader);

        String previousCorrelationId = MDC.get(MDC_CORRELATION_ID_KEY);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        MDC.put(MDC_CORRELATION_ID_KEY, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousCorrelationId != null) {
                MDC.put(MDC_CORRELATION_ID_KEY, previousCorrelationId);
            } else {
                MDC.remove(MDC_CORRELATION_ID_KEY);
            }
        }
    }

    public static String sanitizeOrGenerate(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String trimmed = raw.trim();
        if (trimmed.length() > MAX_CORRELATION_ID_LENGTH) {
            return UUID.randomUUID().toString();
        }

        if (!VALID_CORRELATION_ID_PATTERN.matcher(trimmed).matches()) {
            return UUID.randomUUID().toString();
        }

        return trimmed;
    }
}