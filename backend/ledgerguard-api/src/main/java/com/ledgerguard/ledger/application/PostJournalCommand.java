package com.ledgerguard.ledger.application;

import com.ledgerguard.ledger.domain.EntryDirection;

import java.util.List;
import java.util.Objects;

/**
 * Immutable command requesting the atomic posting of a balanced double-entry journal transaction.
 */
public record PostJournalCommand(List<PostingLine> lines) {

    public PostJournalCommand {
        Objects.requireNonNull(lines, "Posting lines list must not be null");
        lines = List.copyOf(lines); // Defensive copy

        if (lines.size() < 2) {
            throw new LedgerPostingException("Journal posting requires at least 2 lines. Provided: " + lines.size());
        }

        long debitSum = 0L;
        long creditSum = 0L;
        int debitCount = 0;
        int creditCount = 0;

        for (PostingLine line : lines) {
            Objects.requireNonNull(line, "Posting line must not be null");
            try {
                if (line.direction() == EntryDirection.DEBIT) {
                    debitSum = Math.addExact(debitSum, line.amount().getMinorUnits());
                    debitCount++;
                } else if (line.direction() == EntryDirection.CREDIT) {
                    creditSum = Math.addExact(creditSum, line.amount().getMinorUnits());
                    creditCount++;
                }
            } catch (ArithmeticException e) {
                throw new LedgerPostingException("Journal amount total exceeds maximum monetary limit: " + e.getMessage(), e);
            }
        }

        if (debitCount < 1) {
            throw new LedgerPostingException("Journal posting must contain at least one DEBIT line");
        }

        if (creditCount < 1) {
            throw new LedgerPostingException("Journal posting must contain at least one CREDIT line");
        }

        if (debitSum != creditSum) {
            throw new LedgerPostingException(
                    "Journal posting is not balanced: total debits (" + debitSum
                            + " minor units) != total credits (" + creditSum + " minor units)");
        }
    }

    public static PostJournalCommand of(List<PostingLine> lines) {
        return new PostJournalCommand(lines);
    }

    public static PostJournalCommand of(PostingLine... lines) {
        Objects.requireNonNull(lines, "Posting lines array must not be null");
        return new PostJournalCommand(List.of(lines));
    }
}
