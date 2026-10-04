package com.ledgerguard.identity.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Bounded in-memory cooldown throttle for password reset requests per email address.
 * Limits email flooding while ensuring equivalent response timings and behavior
 * for both existing and non-existing accounts.
 * <p>
 * Bound: max 10,000 entries, expires 1 hour after write.
 */
@Component
public class PasswordRecoveryThrottle {

    private final PasswordRecoveryProperties properties;
    private final Cache<String, Instant> emailLastRequestCache;

    public PasswordRecoveryThrottle(PasswordRecoveryProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.emailLastRequestCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofHours(1))
                .build();
    }

    /**
     * Checks whether a request for the normalized email is allowed.
     * If allowed, updates the timestamp and returns true.
     * If in cooldown, returns false.
     */
    public synchronized boolean tryAcquire(String normalizedEmail) {
        Instant now = Instant.now();
        Instant lastTime = emailLastRequestCache.getIfPresent(normalizedEmail);
        if (lastTime != null) {
            Duration elapsed = Duration.between(lastTime, now);
            if (elapsed.compareTo(properties.getPerEmailCooldown()) < 0) {
                return false;
            }
        }
        emailLastRequestCache.put(normalizedEmail, now);
        return true;
    }
}
