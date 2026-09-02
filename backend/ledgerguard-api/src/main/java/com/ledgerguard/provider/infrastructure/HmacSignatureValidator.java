package com.ledgerguard.provider.infrastructure;

import com.ledgerguard.provider.application.ProviderAuthenticationException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Component
public class HmacSignatureValidator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("^sha256=[0-9a-f]{64}$");

    private final WebhookSecurityProperties properties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public HmacSignatureValidator(WebhookSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public HmacSignatureValidator(WebhookSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void validate(String timestampHeader, String signatureHeader, byte[] rawBody) {
        if (timestampHeader == null || timestampHeader.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            throw new ProviderAuthenticationException("Missing required webhook headers");
        }

        if (rawBody == null) {
            rawBody = new byte[0];
        }

        // 1. Timestamp parsing & overflow-safe range validation
        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestampHeader);
        } catch (NumberFormatException ex) {
            throw new ProviderAuthenticationException("Invalid timestamp header: " + timestampHeader);
        }

        Instant receivedTime;
        try {
            receivedTime = Instant.ofEpochSecond(timestampSeconds);
        } catch (DateTimeException | ArithmeticException ex) {
            throw new ProviderAuthenticationException("Timestamp out of valid epoch range: " + timestampHeader);
        }

        Instant now = clock.instant();
        Instant earliest;
        Instant latest;
        try {
            earliest = now.minusSeconds(properties.getMaxClockSkewSeconds());
            latest = now.plusSeconds(properties.getMaxClockSkewSeconds());
        } catch (DateTimeException | ArithmeticException ex) {
            throw new ProviderAuthenticationException("Error calculating clock skew window");
        }

        if (receivedTime.isBefore(earliest) || receivedTime.isAfter(latest)) {
            throw new ProviderAuthenticationException("Webhook timestamp outside allowed clock skew window");
        }

        // 2. Signature syntax validation
        if (!SIGNATURE_PATTERN.matcher(signatureHeader).matches()) {
            throw new ProviderAuthenticationException("Invalid signature header format: " + signatureHeader);
        }

        // 3. HMAC computation over raw received bytes
        byte[] timestampBytes = timestampHeader.getBytes(StandardCharsets.UTF_8);
        byte[] dotBytes = ".".getBytes(StandardCharsets.UTF_8);
        byte[] canonicalBytes = new byte[timestampBytes.length + dotBytes.length + rawBody.length];

        System.arraycopy(timestampBytes, 0, canonicalBytes, 0, timestampBytes.length);
        System.arraycopy(dotBytes, 0, canonicalBytes, timestampBytes.length, dotBytes.length);
        System.arraycopy(rawBody, 0, canonicalBytes, timestampBytes.length + dotBytes.length, rawBody.length);

        byte[] computedDigest;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            computedDigest = mac.doFinal(canonicalBytes);
        } catch (Exception ex) {
            throw new ProviderAuthenticationException("Failed to compute HMAC signature: " + ex.getMessage());
        }

        // 4. Constant-time digest comparison
        String hexDigest = signatureHeader.substring("sha256=".length());
        byte[] receivedDigest;
        try {
            receivedDigest = HexFormat.of().parseHex(hexDigest);
        } catch (Exception ex) {
            throw new ProviderAuthenticationException("Failed to parse signature digest");
        }

        if (!MessageDigest.isEqual(computedDigest, receivedDigest)) {
            throw new ProviderAuthenticationException("Webhook signature verification failed");
        }
    }
}
