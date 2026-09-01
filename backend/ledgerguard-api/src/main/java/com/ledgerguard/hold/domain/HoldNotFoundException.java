package com.ledgerguard.hold.domain;

import java.util.UUID;

public class HoldNotFoundException extends RuntimeException {
    public HoldNotFoundException(UUID holdId) {
        super("Balance hold not found: " + holdId);
    }
}
