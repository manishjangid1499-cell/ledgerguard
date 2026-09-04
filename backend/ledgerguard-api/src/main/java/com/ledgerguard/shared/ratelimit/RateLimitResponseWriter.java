package com.ledgerguard.shared.ratelimit;

import com.ledgerguard.shared.error.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Serializes standardized RFC 9457 ProblemDetail HTTP 429 responses from servlet filters.
 */
@Component
public class RateLimitResponseWriter {

    private final ObjectMapper objectMapper;

    public RateLimitResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeRateLimitResponse(
            HttpServletRequest request,
            HttpServletResponse response,
            long retryAfterSeconds
    ) throws IOException {
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                status,
                "Rate limit exceeded. Please retry after " + retryAfterSeconds + " seconds."
        );
        problemDetail.setType(URI.create("urn:ledgerguard:error:rate-limit-exceeded"));
        problemDetail.setTitle("Too Many Requests");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("errorCode", ApiErrorCode.RATE_LIMIT_EXCEEDED);
        problemDetail.setProperty("timestamp", Instant.now());

        objectMapper.writeValue(response.getWriter(), problemDetail);
    }
}
