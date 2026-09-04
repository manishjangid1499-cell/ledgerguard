package com.ledgerguard.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Manages Caffeine-backed in-memory token buckets for API admission control.
 */
@Service
public class RateLimitService {

    private final RateLimitProperties properties;
    private final Cache<String, Bucket> bucketCache;

    public RateLimitService(RateLimitProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        RateLimitProperties.Store store = properties.getStore();
        this.bucketCache = Caffeine.newBuilder()
                .maximumSize(store.getMaxEntries())
                .expireAfterAccess(store.getIdleTtl())
                .build();
    }

    /**
     * Attempts to consume 1 token for the specified client key under the given policy.
     *
     * @param key    the bounded client bucket key (e.g. "FINANCIAL_WRITE:user:<UUID>")
     * @param policy the policy determining capacity and refill rates
     * @return the consumption probe with consumption status and wait duration
     */
    public ConsumptionProbe tryConsume(String key, RateLimitPolicy policy) {
        if (!properties.isEnabled() || policy == RateLimitPolicy.EXEMPT) {
            return null; // Exempt or disabled; admitted without bucket consumption
        }

        Bucket bucket = bucketCache.get(key, k -> buildBucket(policy));
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    private Bucket buildBucket(RateLimitPolicy policy) {
        RateLimitProperties.BandwidthConfig config = resolveConfig(policy);
        Bandwidth limit = Bandwidth.builder()
                .capacity(config.getCapacity())
                .refillGreedy(config.getRefillTokens(), config.getRefillPeriod())
                .build();

        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    private RateLimitProperties.BandwidthConfig resolveConfig(RateLimitPolicy policy) {
        RateLimitProperties.Policy p = properties.getPolicy();
        return switch (policy) {
            case PUBLIC_AUTH -> p.getPublicAuth();
            case FINANCIAL_WRITE -> p.getFinancialWrite();
            case OPS -> p.getOps();
            case AUTHENTICATED_GENERAL, EXEMPT -> p.getAuthenticatedGeneral();
        };
    }

    public Cache<String, Bucket> getBucketCache() {
        return bucketCache;
    }

    public void cleanUp() {
        bucketCache.cleanUp();
    }
}
