package com.ledgerguard.shared.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @Test
    @DisplayName("Preserves valid client-supplied UUID correlation ID")
    void preservesValidUuidCorrelationId() throws ServletException, IOException {
        String inputCorrelationId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, inputCorrelationId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcValueDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            mdcValueDuringChain.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY));
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(inputCorrelationId);
        assertThat(mdcValueDuringChain.get()).isEqualTo(inputCorrelationId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Preserves valid client-supplied bounded alphanumeric token")
    void preservesValidAlphanumericCorrelationId() throws ServletException, IOException {
        String token = "client-tx_123456789";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcValueDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            mdcValueDuringChain.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY));
        };

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(token);
        assertThat(mdcValueDuringChain.get()).isEqualTo(token);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Generates new UUID when correlation ID header is missing")
    void generatesUuidWhenHeaderMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcValue = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcValue.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY));

        filter.doFilterInternal(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(mdcValue.get()).isEqualTo(generated);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Replaces blank correlation ID with clean UUID")
    void replacesBlankCorrelationId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {});

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    @DisplayName("Sanitizes and replaces correlation ID containing CRLF or control characters")
    void sanitizesControlCharacters() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "evil\r\nHeader-Injection");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {});

        String output = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(output).isNotEqualTo("evil\r\nHeader-Injection");
        assertThat(UUID.fromString(output)).isNotNull();
    }

    @Test
    @DisplayName("Sanitizes and replaces oversized correlation ID (>64 characters)")
    void sanitizesOversizedCorrelationId() throws ServletException, IOException {
        String longId = "a".repeat(65);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, longId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {});

        String output = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(output).isNotEqualTo(longId);
        assertThat(UUID.fromString(output)).isNotNull();
    }

    @Test
    @DisplayName("Cleans up MDC even if filter chain throws exception")
    void cleansUpMdcOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain throwingChain = (req, res) -> {
            throw new RuntimeException("Simulated filter chain failure");
        };

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, throwingChain))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated filter chain failure");

        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Proves sequential requests on same thread have zero MDC leakage across requests")
    void sequentialRequestsOnSameThreadHaveNoMdcLeakage() throws ServletException, IOException {
        String corrA = "corr-req-A-111";
        String corrB = "corr-req-B-222";

        MockHttpServletRequest reqA = new MockHttpServletRequest();
        reqA.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, corrA);
        MockHttpServletResponse resA = new MockHttpServletResponse();

        AtomicReference<String> observedMdcA = new AtomicReference<>();
        filter.doFilterInternal(reqA, resA, (req, res) -> observedMdcA.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)));

        assertThat(observedMdcA.get()).isEqualTo(corrA);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();

        MockHttpServletRequest reqB = new MockHttpServletRequest();
        reqB.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, corrB);
        MockHttpServletResponse resB = new MockHttpServletResponse();

        AtomicReference<String> observedMdcB = new AtomicReference<>();
        filter.doFilterInternal(reqB, resB, (req, res) -> observedMdcB.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)));

        assertThat(observedMdcB.get()).isEqualTo(corrB);
        assertThat(observedMdcB.get()).isNotEqualTo(corrA);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Restores pre-existing outer MDC context on filter completion")
    void restoresOuterMdcContext() throws ServletException, IOException {
        String outerCorr = "outer-context-999";
        String innerCorr = "inner-request-111";
        MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID_KEY, outerCorr);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, innerCorr);
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<String> insideChain = new AtomicReference<>();
        filter.doFilterInternal(req, res, (r, s) -> insideChain.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)));

        assertThat(insideChain.get()).isEqualTo(innerCorr);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isEqualTo(outerCorr);
    }

    @Test
    @DisplayName("Sanitizes headers with quotes, html brackets, spaces, null bytes, and DEL")
    void sanitizesSpecialDisallowedCharacters() throws ServletException, IOException {
        String[] invalidHeaders = {
                "corr<script>alert(1)</script>",
                "corr\"quote\"",
                "corr'single'",
                "corr id with spaces",
                "corr\u0000nullbyte",
                "corr\u007Fdel"
        };

        for (String invalid : invalidHeaders) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, invalid);
            MockHttpServletResponse res = new MockHttpServletResponse();

            filter.doFilterInternal(req, res, (r, s) -> {});

            String output = res.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
            assertThat(output).isNotEqualTo(invalid);
            assertThat(UUID.fromString(output)).isNotNull();
        }
    }
}