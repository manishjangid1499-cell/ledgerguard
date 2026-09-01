package com.ledgerguard.hold.application;

import com.ledgerguard.hold.domain.BalanceHold;
import com.ledgerguard.hold.infrastructure.BalanceHoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Service to expire balance holds whose expiration timestamp has passed.
 * Multi-instance safe via conditional database updates.
 */
@Service
public class HoldExpirationService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirationService.class);

    private final BalanceHoldRepository balanceHoldRepository;

    public HoldExpirationService(BalanceHoldRepository balanceHoldRepository) {
        this.balanceHoldRepository = balanceHoldRepository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public int expireDueHolds(Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");

        List<BalanceHold> dueHolds = balanceHoldRepository.findDueActiveHolds(now);
        if (dueHolds.isEmpty()) {
            return 0;
        }

        int expiredCount = 0;
        for (BalanceHold hold : dueHolds) {
            int updated = balanceHoldRepository.expireHoldConditional(hold.getId(), now);
            if (updated > 0) {
                expiredCount++;
                log.info("Expired balance hold: id={}, account={}, amount={}, expiresAt={}",
                        hold.getId(), hold.getLedgerAccountId(), hold.getAmountMinor(), hold.getExpiresAt());
            }
        }

        return expiredCount;
    }
}
