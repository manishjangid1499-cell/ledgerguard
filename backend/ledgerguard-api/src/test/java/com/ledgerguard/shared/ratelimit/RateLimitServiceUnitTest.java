package com.ledgerguard.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceUnitTest {

    @Test
    @DisplayName("Service consumes tokens and reports probe correctly")
    void testTokenConsumption() {
        RateLimitProperties props = new RateLimitProperties();
        props.getPolicy().getFinancialWrite().setCapacity(3);
        props.getPolicy().getFinancialWrite().setRefillTokens(3);
        props.getPolicy().getFinancialWrite().setRefillPeriod(Duration.ofMinutes(1));

        RateLimitService service = new RateLimitService(props);
        String key = "FINANCIAL_WRITE:user:11111111-1111-1111-1111-111111111111";

        ConsumptionProbe p1 = service.tryConsume(key, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(p1).isNotNull();
        assertThat(p1.isConsumed()).isTrue();
        assertThat(p1.getRemainingTokens()).isEqualTo(2);

        ConsumptionProbe p2 = service.tryConsume(key, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(p2.isConsumed()).isTrue();
        assertThat(p2.getRemainingTokens()).isEqualTo(1);

        ConsumptionProbe p3 = service.tryConsume(key, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(p3.isConsumed()).isTrue();
        assertThat(p3.getRemainingTokens()).isEqualTo(0);

        ConsumptionProbe p4 = service.tryConsume(key, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(p4.isConsumed()).isFalse();
        assertThat(p4.getNanosToWaitForRefill()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("EXEMPT policy returns null without consuming tokens")
    void testExemptPolicy() {
        RateLimitProperties props = new RateLimitProperties();
        RateLimitService service = new RateLimitService(props);

        ConsumptionProbe probe = service.tryConsume("EXEMPT:test", RateLimitPolicy.EXEMPT);
        assertThat(probe).isNull();
    }

    @Test
    @DisplayName("Disabled rate limiting returns null without consuming tokens")
    void testDisabledLimiter() {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(false);
        RateLimitService service = new RateLimitService(props);

        ConsumptionProbe probe = service.tryConsume("FINANCIAL_WRITE:user:1", RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(probe).isNull();
    }

    @Test
    @DisplayName("Caffeine cache bounds entries to configured max entries with size-based eviction")
    void testBoundedCacheSize() {
        RateLimitProperties props = new RateLimitProperties();
        props.getStore().setMaxEntries(5);
        RateLimitService service = new RateLimitService(props);

        for (int i = 0; i < 20; i++) {
            service.tryConsume("PUBLIC_AUTH:ip:192.168.1." + i, RateLimitPolicy.PUBLIC_AUTH);
        }

        service.cleanUp();
        Cache<String, Bucket> cache = service.getBucketCache();
        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(5L);
    }

    @Test
    @DisplayName("Same authenticated User A: different policies have isolated bucket instances and independent capacities")
    void testSameUserCrossPolicyIsolation() {
        RateLimitProperties props = new RateLimitProperties();
        props.getPolicy().getFinancialWrite().setCapacity(2);
        props.getPolicy().getFinancialWrite().setRefillTokens(2);
        props.getPolicy().getFinancialWrite().setRefillPeriod(Duration.ofMinutes(1));

        props.getPolicy().getAuthenticatedGeneral().setCapacity(4);
        props.getPolicy().getAuthenticatedGeneral().setRefillTokens(4);
        props.getPolicy().getAuthenticatedGeneral().setRefillPeriod(Duration.ofMinutes(1));

        RateLimitService service = new RateLimitService(props);
        String userId = "22222222-2222-2222-2222-222222222222";
        String financialKey = "FINANCIAL_WRITE:user:" + userId;
        String generalKey = "AUTHENTICATED_GENERAL:user:" + userId;

        // 1. Consume FINANCIAL_WRITE capacity completely
        ConsumptionProbe fw1 = service.tryConsume(financialKey, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(fw1.isConsumed()).isTrue();
        assertThat(fw1.getRemainingTokens()).isEqualTo(1);

        ConsumptionProbe fw2 = service.tryConsume(financialKey, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(fw2.isConsumed()).isTrue();
        assertThat(fw2.getRemainingTokens()).isEqualTo(0);

        ConsumptionProbe fw3 = service.tryConsume(financialKey, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(fw3.isConsumed()).isFalse();

        // 2. Call AUTHENTICATED_GENERAL for the same user: quota must be completely unaffected
        for (int i = 0; i < 4; i++) {
            ConsumptionProbe gen = service.tryConsume(generalKey, RateLimitPolicy.AUTHENTICATED_GENERAL);
            assertThat(gen.isConsumed()).isTrue();
            assertThat(gen.getRemainingTokens()).isEqualTo(3 - i);
        }
        ConsumptionProbe gen5 = service.tryConsume(generalKey, RateLimitPolicy.AUTHENTICATED_GENERAL);
        assertThat(gen5.isConsumed()).isFalse();

        // 3. Test reverse order on a new user User B: GENERAL first, then FINANCIAL_WRITE
        String userB = "33333333-3333-3333-3333-333333333333";
        String genKeyB = "AUTHENTICATED_GENERAL:user:" + userB;
        String finKeyB = "FINANCIAL_WRITE:user:" + userB;

        for (int i = 0; i < 4; i++) {
            ConsumptionProbe gen = service.tryConsume(genKeyB, RateLimitPolicy.AUTHENTICATED_GENERAL);
            assertThat(gen.isConsumed()).isTrue();
        }
        assertThat(service.tryConsume(genKeyB, RateLimitPolicy.AUTHENTICATED_GENERAL).isConsumed()).isFalse();

        // Now verify FINANCIAL_WRITE gets its own full configured capacity (2)
        ConsumptionProbe fb1 = service.tryConsume(finKeyB, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(fb1.isConsumed()).isTrue();
        assertThat(fb1.getRemainingTokens()).isEqualTo(1);

        ConsumptionProbe fb2 = service.tryConsume(finKeyB, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(fb2.isConsumed()).isTrue();
        assertThat(fb2.getRemainingTokens()).isEqualTo(0);

        ConsumptionProbe fb3 = service.tryConsume(finKeyB, RateLimitPolicy.FINANCIAL_WRITE);
        assertThat(fb3.isConsumed()).isFalse();
    }
}
