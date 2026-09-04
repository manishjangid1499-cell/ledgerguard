package com.ledgerguard.funding.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for outbound provider resilience (Resilience4j).
 * Enforces startup validation on all retry, bulkhead, and circuit-breaker thresholds.
 */
@Component
@ConfigurationProperties(prefix = "ledgerguard.resilience")
public class ProviderResilienceProperties {

    private final Retry retry = new Retry();
    private final Bulkhead bulkhead = new Bulkhead();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public Retry getRetry() {
        return retry;
    }

    public Bulkhead getBulkhead() {
        return bulkhead;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    @PostConstruct
    public void validate() {
        retry.validate();
        bulkhead.validate();
        circuitBreaker.validate();
    }

    public static class Retry {
        private final Policy create = new Policy(3, Duration.ofMillis(200), 2.0, Duration.ofMillis(400), 0.20);
        private final Policy status = new Policy(3, Duration.ofMillis(100), 2.0, Duration.ofMillis(200), 0.20);

        public Policy getCreate() {
            return create;
        }

        public Policy getStatus() {
            return status;
        }

        public void validate() {
            create.validate("retry.create");
            status.validate("retry.status");
        }

        public static class Policy {
            private int maxAttempts = 3;
            private Duration initialBackoff = Duration.ofMillis(200);
            private double multiplier = 2.0;
            private Duration maxBackoff = Duration.ofMillis(400);
            private double jitterFactor = 0.20;

            public Policy() {}

            public Policy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff, double jitterFactor) {
                this.maxAttempts = maxAttempts;
                this.initialBackoff = initialBackoff;
                this.multiplier = multiplier;
                this.maxBackoff = maxBackoff;
                this.jitterFactor = jitterFactor;
            }

            public int getMaxAttempts() {
                return maxAttempts;
            }

            public void setMaxAttempts(int maxAttempts) {
                this.maxAttempts = maxAttempts;
            }

            public Duration getInitialBackoff() {
                return initialBackoff;
            }

            public void setInitialBackoff(Duration initialBackoff) {
                this.initialBackoff = initialBackoff;
            }

            public double getMultiplier() {
                return multiplier;
            }

            public void setMultiplier(double multiplier) {
                this.multiplier = multiplier;
            }

            public Duration getMaxBackoff() {
                return maxBackoff;
            }

            public void setMaxBackoff(Duration maxBackoff) {
                this.maxBackoff = maxBackoff;
            }

            public double getJitterFactor() {
                return jitterFactor;
            }

            public void setJitterFactor(double jitterFactor) {
                this.jitterFactor = jitterFactor;
            }

            public void validate(String prefix) {
                if (maxAttempts < 1) {
                    throw new IllegalStateException(prefix + ".maxAttempts must be >= 1, was: " + maxAttempts);
                }
                if (initialBackoff == null || initialBackoff.isNegative()) {
                    throw new IllegalStateException(prefix + ".initialBackoff must be non-negative");
                }
                if (multiplier < 1.0) {
                    throw new IllegalStateException(prefix + ".multiplier must be >= 1.0, was: " + multiplier);
                }
                if (maxBackoff == null || maxBackoff.isNegative() || maxBackoff.minus(initialBackoff).isNegative()) {
                    throw new IllegalStateException(prefix + ".maxBackoff must be >= initialBackoff");
                }
                if (jitterFactor < 0.0 || jitterFactor > 1.0) {
                    throw new IllegalStateException(prefix + ".jitterFactor must be in [0.0, 1.0], was: " + jitterFactor);
                }
            }
        }
    }

    public static class Bulkhead {
        private final Config create = new Config(20, Duration.ZERO);
        private final Config status = new Config(20, Duration.ZERO);

        public Config getCreate() {
            return create;
        }

        public Config getStatus() {
            return status;
        }

        public void validate() {
            create.validate("bulkhead.create");
            status.validate("bulkhead.status");
        }

        public static class Config {
            private int maxConcurrentCalls = 20;
            private Duration maxWaitDuration = Duration.ZERO;

            public Config() {}

            public Config(int maxConcurrentCalls, Duration maxWaitDuration) {
                this.maxConcurrentCalls = maxConcurrentCalls;
                this.maxWaitDuration = maxWaitDuration;
            }

            public int getMaxConcurrentCalls() {
                return maxConcurrentCalls;
            }

            public void setMaxConcurrentCalls(int maxConcurrentCalls) {
                this.maxConcurrentCalls = maxConcurrentCalls;
            }

            public Duration getMaxWaitDuration() {
                return maxWaitDuration;
            }

            public void setMaxWaitDuration(Duration maxWaitDuration) {
                this.maxWaitDuration = maxWaitDuration;
            }

            public void validate(String prefix) {
                if (maxConcurrentCalls < 1) {
                    throw new IllegalStateException(prefix + ".maxConcurrentCalls must be >= 1, was: " + maxConcurrentCalls);
                }
                if (maxWaitDuration == null || maxWaitDuration.isNegative()) {
                    throw new IllegalStateException(prefix + ".maxWaitDuration must be non-negative");
                }
            }
        }
    }

    public static class CircuitBreaker {
        private String name = "psp-remote";
        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 10;
        private float failureRateThreshold = 50.0f;
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);
        private int permittedNumberOfCallsInHalfOpenState = 5;
        private boolean automaticTransitionFromOpenToHalfOpenEnabled = false;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }

        public boolean isAutomaticTransitionFromOpenToHalfOpenEnabled() {
            return automaticTransitionFromOpenToHalfOpenEnabled;
        }

        public void setAutomaticTransitionFromOpenToHalfOpenEnabled(boolean automaticTransitionFromOpenToHalfOpenEnabled) {
            this.automaticTransitionFromOpenToHalfOpenEnabled = automaticTransitionFromOpenToHalfOpenEnabled;
        }

        public void validate() {
            if (slidingWindowSize < 1) {
                throw new IllegalStateException("circuitBreaker.slidingWindowSize must be >= 1, was: " + slidingWindowSize);
            }
            if (minimumNumberOfCalls < 1 || minimumNumberOfCalls > slidingWindowSize) {
                throw new IllegalStateException("circuitBreaker.minimumNumberOfCalls must be between 1 and slidingWindowSize ("
                        + slidingWindowSize + "), was: " + minimumNumberOfCalls);
            }
            if (failureRateThreshold <= 0.0f || failureRateThreshold > 100.0f) {
                throw new IllegalStateException("circuitBreaker.failureRateThreshold must be in (0.0, 100.0], was: " + failureRateThreshold);
            }
            if (waitDurationInOpenState == null || waitDurationInOpenState.isNegative()) {
                throw new IllegalStateException("circuitBreaker.waitDurationInOpenState must be non-negative");
            }
            if (permittedNumberOfCallsInHalfOpenState < 1) {
                throw new IllegalStateException("circuitBreaker.permittedNumberOfCallsInHalfOpenState must be >= 1, was: "
                        + permittedNumberOfCallsInHalfOpenState);
            }
        }
    }
}
