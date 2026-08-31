package com.ledgerguard.ledger.application;

import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostJournalCommandTest {

    private final UUID account1 = UUID.randomUUID();
    private final UUID account2 = UUID.randomUUID();

    @Test
    @DisplayName("Valid 2-line balanced command is constructed successfully")
    void validTwoLineCommandConstructed() {
        PostJournalCommand command = PostJournalCommand.of(
                PostingLine.debit(account1, 10000L),
                PostingLine.credit(account2, 10000L)
        );

        assertThat(command.lines()).hasSize(2);
        assertThat(command.lines().get(0).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(command.lines().get(0).amount()).isEqualTo(Money.inr(10000L));
        assertThat(command.lines().get(1).direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(command.lines().get(1).amount()).isEqualTo(Money.inr(10000L));
    }

    @Test
    @DisplayName("Posting lines list is defensively copied and immutable")
    void postingLinesListIsDefensivelyCopied() {
        PostingLine line1 = PostingLine.debit(account1, 10000L);
        PostingLine line2 = PostingLine.credit(account2, 10000L);
        List<PostingLine> mutableList = new java.util.ArrayList<>(List.of(line1, line2));

        PostJournalCommand command = PostJournalCommand.of(mutableList);
        mutableList.clear();

        assertThat(command.lines()).hasSize(2);
        assertThatThrownBy(() -> command.lines().add(PostingLine.debit(UUID.randomUUID(), 100L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Command with fewer than 2 lines is rejected")
    void commandWithFewerThanTwoLinesRejected() {
        assertThatThrownBy(() -> PostJournalCommand.of(List.of()))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("requires at least 2 lines");

        assertThatThrownBy(() -> PostJournalCommand.of(PostingLine.debit(account1, 10000L)))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("requires at least 2 lines");
    }

    @Test
    @DisplayName("Unbalanced command is rejected by application validation")
    void unbalancedCommandRejected() {
        assertThatThrownBy(() -> PostJournalCommand.of(
                PostingLine.debit(account1, 10000L),
                PostingLine.credit(account2, 9000L)
        )).isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("is not balanced");
    }

    @Test
    @DisplayName("Single-sided command with only DEBIT lines is rejected")
    void singleSidedDebitsRejected() {
        assertThatThrownBy(() -> PostJournalCommand.of(
                PostingLine.debit(account1, 5000L),
                PostingLine.debit(account2, 5000L)
        )).isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("must contain at least one CREDIT line");
    }

    @Test
    @DisplayName("Single-sided command with only CREDIT lines is rejected")
    void singleSidedCreditsRejected() {
        assertThatThrownBy(() -> PostJournalCommand.of(
                PostingLine.credit(account1, 5000L),
                PostingLine.credit(account2, 5000L)
        )).isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("must contain at least one DEBIT line");
    }

    @Test
    @DisplayName("Posting line with zero amount is rejected")
    void zeroAmountLineRejected() {
        assertThatThrownBy(() -> PostingLine.debit(account1, 0L))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    @DisplayName("Posting line with negative amount is rejected")
    void negativeAmountLineRejected() {
        assertThatThrownBy(() -> PostingLine.credit(account1, -500L))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    @DisplayName("Posting line with non-INR currency is rejected")
    void nonInrCurrencyRejected() {
        Money usdMoney = Money.ofMinor(1000L, Currency.getInstance("USD"));
        assertThatThrownBy(() -> new PostingLine(account1, EntryDirection.DEBIT, usdMoney))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("currency must be INR");
    }

    @Test
    @DisplayName("Checked accumulation overflow throws exception")
    void accumulationOverflowThrowsException() {
        PostingLine largeDebit1 = PostingLine.debit(account1, Long.MAX_VALUE);
        PostingLine largeDebit2 = PostingLine.debit(account1, 1L);
        PostingLine largeCredit = PostingLine.credit(account2, 100L);

        assertThatThrownBy(() -> PostJournalCommand.of(largeDebit1, largeDebit2, largeCredit))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("exceeds maximum monetary limit");
    }
}
