package com.ledgerguard.ledger.application;

import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents an individual debit or credit line item in a posting command.
 */
public record PostingLine(
        UUID ledgerAccountId,
        EntryDirection direction,
        Money amount
) {
    public PostingLine {
        Objects.requireNonNull(ledgerAccountId, "Ledger account ID must not be null");
        Objects.requireNonNull(direction, "Entry direction must not be null");
        Objects.requireNonNull(amount, "Posting amount must not be null");

        if (!"INR".equals(amount.getCurrencyCode())) {
            throw new LedgerPostingException("Posting line currency must be INR. Provided: " + amount.getCurrencyCode());
        }

        if (amount.getMinorUnits() <= 0L) {
            throw new LedgerPostingException("Posting line amount must be strictly positive (> 0 minor units). Provided: " + amount.getMinorUnits());
        }
    }

    public static PostingLine debit(UUID ledgerAccountId, Money amount) {
        return new PostingLine(ledgerAccountId, EntryDirection.DEBIT, amount);
    }

    public static PostingLine debit(UUID ledgerAccountId, long minorUnits) {
        return new PostingLine(ledgerAccountId, EntryDirection.DEBIT, Money.inr(minorUnits));
    }

    public static PostingLine credit(UUID ledgerAccountId, Money amount) {
        return new PostingLine(ledgerAccountId, EntryDirection.CREDIT, amount);
    }

    public static PostingLine credit(UUID ledgerAccountId, long minorUnits) {
        return new PostingLine(ledgerAccountId, EntryDirection.CREDIT, Money.inr(minorUnits));
    }
}
