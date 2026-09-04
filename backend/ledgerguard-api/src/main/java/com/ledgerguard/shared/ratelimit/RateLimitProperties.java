package com.ledgerguard.shared.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Strongly-typed configuration properties for token-bucket rate limiting and bounded cache.
 */
@ConfigurationProperties(prefix = "ledgerguard.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Store store = new Store();
    private Policy policy = new Policy();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(Policy policy) {
        this.policy = policy;
    }

    public static class Store {
        private long maxEntries = 10_000L;
        private Duration idleTtl = Duration.ofHours(1);

        public long getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(long maxEntries) {
            if (maxEntries < 1) {
                throw new IllegalArgumentException("maxEntries must be >= 1");
            }
            this.maxEntries = maxEntries;
        }

        public Duration getIdleTtl() {
            return idleTtl;
        }

        public void setIdleTtl(Duration idleTtl) {
            if (idleTtl == null || idleTtl.isZero() || idleTtl.isNegative()) {
                throw new IllegalArgumentException("idleTtl must be strictly positive");
            }
            this.idleTtl = idleTtl;
        }
    }

    public static class Policy {
        private BandwidthConfig publicAuth = new BandwidthConfig(10, 10, Duration.ofMinutes(1));
        private BandwidthConfig financialWrite = new BandwidthConfig(20, 20, Duration.ofMinutes(1));
        private BandwidthConfig ops = new BandwidthConfig(30, 30, Duration.ofMinutes(1));
        private BandwidthConfig authenticatedGeneral = new BandwidthConfig(50, 50, Duration.ofMinutes(1));

        public BandwidthConfig getPublicAuth() {
            return publicAuth;
        }

        public void setPublicAuth(BandwidthConfig publicAuth) {
            this.publicAuth = publicAuth;
        }

        public BandwidthConfig getFinancialWrite() {
            return financialWrite;
        }

        public void setFinancialWrite(BandwidthConfig financialWrite) {
            this.financialWrite = financialWrite;
        }

        public BandwidthConfig getOps() {
            return ops;
        }

        public void setOps(BandwidthConfig ops) {
            this.ops = ops;
        }

        public BandwidthConfig getAuthenticatedGeneral() {
            return authenticatedGeneral;
        }

        public void setAuthenticatedGeneral(BandwidthConfig authenticatedGeneral) {
            this.authenticatedGeneral = authenticatedGeneral;
        }
    }

    public static class BandwidthConfig {
        private long capacity;
        private long refillTokens;
        private Duration refillPeriod;

        public BandwidthConfig() {
            this(10, 10, Duration.ofMinutes(1));
        }

        public BandwidthConfig(long capacity, long refillTokens, Duration refillPeriod) {
            setCapacity(capacity);
            setRefillTokens(refillTokens);
            setRefillPeriod(refillPeriod);
        }

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            if (capacity < 1) {
                throw new IllegalArgumentException("capacity must be >= 1");
            }
            this.capacity = capacity;
        }

        public long getRefillTokens() {
            return refillTokens;
        }

        public void setRefillTokens(long refillTokens) {
            if (refillTokens < 1) {
                throw new IllegalArgumentException("refillTokens must be >= 1");
            }
            this.refillTokens = refillTokens;
        }

        public Duration getRefillPeriod() {
            return refillPeriod;
        }

        public void setRefillPeriod(Duration refillPeriod) {
            if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
                throw new IllegalArgumentException("refillPeriod must be strictly positive");
            }
            this.refillPeriod = refillPeriod;
        }
    }
}
