package com.ledgerguard.featureflags;

import com.featureflag.sdk.FeatureFlagClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/features")
public class FeatureAvailabilityController {

    private final ObjectProvider<FeatureFlagClient> clientProvider;

    public FeatureAvailabilityController(
            ObjectProvider<FeatureFlagClient> clientProvider
    ) {
        this.clientProvider = clientProvider;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'OPS')")
    public ResponseEntity<FeatureAvailabilityResponse> getFeatures(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = UUID.fromString(jwt.getSubject());
        FeatureFlagClient client = clientProvider.getIfAvailable();

        boolean darkModeAvailable = client != null
                && client.isEnabled(
                "ledgerguard_dark_mode",
                "ledgerguard:" + userId,
                false
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new FeatureAvailabilityResponse(darkModeAvailable));
    }

    public record FeatureAvailabilityResponse(
            boolean darkModeAvailable
    ) {
    }
}