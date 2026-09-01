package com.ledgerguard.hold.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Background scheduler periodically polling for due active holds and transitioning them to EXPIRED.
 */
@Component
public class HoldExpirationScheduler {

    private final HoldExpirationService holdExpirationService;

    public HoldExpirationScheduler(HoldExpirationService holdExpirationService) {
        this.holdExpirationService = holdExpirationService;
    }

    @Scheduled(fixedDelayString = "${ledgerguard.hold.expiration-delay-ms:60000}")
    public void scheduleHoldExpiration() {
        holdExpirationService.expireDueHolds(Instant.now());
    }
}
