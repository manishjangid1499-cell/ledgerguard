package com.ledgerguard.featureflags;

import com.featureflag.sdk.FeatureFlagClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class FeatureFlagConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "ledgerguard.feature-flags.enabled",
            havingValue = "true"
    )
    public FeatureFlagClient featureFlagClient(
            @Value("${FEATURE_FLAG_BASE_URL}") String baseUrl,
            @Value("${FEATURE_FLAG_SDK_KEY}") String sdkKey
    ) {
        return FeatureFlagClient.builder()
                .baseUrl(baseUrl)
                .sdkKey(sdkKey)
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }
}