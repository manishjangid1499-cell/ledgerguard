package com.ledgerguard.transfer.domain;

import java.util.UUID;

/**
 * Exception thrown when a transfer destination ledger account cannot be found.
 */
public class TransferDestinationNotFoundException extends RuntimeException {

    private final UUID destinationAccountId;

    public TransferDestinationNotFoundException(UUID destinationAccountId) {
        super("Destination ledger account not found: " + destinationAccountId);
        this.destinationAccountId = destinationAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
    }
}
