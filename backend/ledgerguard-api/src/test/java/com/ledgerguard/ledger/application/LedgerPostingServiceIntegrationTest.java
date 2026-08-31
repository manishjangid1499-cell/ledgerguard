package com.ledgerguard.ledger.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.EntryDirection;
import com.ledgerguard.ledger.domain.JournalEntry;
import com.ledgerguard.ledger.domain.JournalStatus;
import com.ledgerguard.ledger.domain.JournalTransaction;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.JournalEntryRepository;
import com.ledgerguard.ledger.infrastructure.JournalTransactionRepository;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostingServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Valid two-line balanced posting atomically persists journal and entries in POSTED status")
    void validTwoLinePost() {
        LedgerAccount reserveAccount = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount clearingAccount = createSystemAccount(AccountType.PSP_CLEARING);

        PostJournalCommand command = PostJournalCommand.of(
                PostingLine.debit(reserveAccount.getId(), 10000L),
                PostingLine.credit(clearingAccount.getId(), 10000L)
        );

        PostingResult result = ledgerPostingService.post(command);

        assertThat(result.journalTransactionId()).isNotNull();
        assertThat(result.postedAt()).isNotNull();

        // Verify JournalTransaction in DB
        JournalTransaction txn = journalTransactionRepository.findById(result.journalTransactionId()).orElseThrow();
        assertThat(txn.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(txn.getPostedAt()).isNotNull();
        assertThat(txn.getCurrency()).isEqualTo("INR");

        // Verify JournalEntries in DB
        List<JournalEntry> entries = journalEntryRepository.findByJournalTransactionId(result.journalTransactionId());
        assertThat(entries).hasSize(2);

        JournalEntry debitEntry = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.DEBIT)
                .findFirst().orElseThrow();
        assertThat(debitEntry.getLedgerAccount().getId()).isEqualTo(reserveAccount.getId());
        assertThat(debitEntry.getAmountMinor()).isEqualTo(10000L);

        JournalEntry creditEntry = entries.stream()
                .filter(e -> e.getDirection() == EntryDirection.CREDIT)
                .findFirst().orElseThrow();
        assertThat(creditEntry.getLedgerAccount().getId()).isEqualTo(clearingAccount.getId());
        assertThat(creditEntry.getAmountMinor()).isEqualTo(10000L);

        // Verify journal transaction is POSTED and not DRAFT
        assertThat(txn.getStatus()).isEqualTo(JournalStatus.POSTED);
    }

    @Test
    @DisplayName("Valid multi-line balanced posting persists all distinct entry lines")
    void validMultiLinePost() {
        LedgerAccount reserveAccount = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount clearingAccount = createSystemAccount(AccountType.PSP_CLEARING);
        LedgerAccount feesAccount = createSystemAccount(AccountType.PLATFORM_FEES);

        PostJournalCommand command = PostJournalCommand.of(
                PostingLine.debit(reserveAccount.getId(), 15000L),
                PostingLine.credit(clearingAccount.getId(), 10000L),
                PostingLine.credit(feesAccount.getId(), 5000L)
        );

        PostingResult result = ledgerPostingService.post(command);

        JournalTransaction txn = journalTransactionRepository.findById(result.journalTransactionId()).orElseThrow();
        assertThat(txn.getStatus()).isEqualTo(JournalStatus.POSTED);

        List<JournalEntry> entries = journalEntryRepository.findByJournalTransactionId(result.journalTransactionId());
        assertThat(entries).hasSize(3);
    }

    @Test
    @DisplayName("Multiple posting lines referencing the same account are persisted as distinct entries")
    void multipleLinesSameAccount() {
        LedgerAccount accountA = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount accountB = createSystemAccount(AccountType.PSP_CLEARING);

        PostJournalCommand command = PostJournalCommand.of(
                PostingLine.debit(accountA.getId(), 6000L),
                PostingLine.debit(accountA.getId(), 4000L),
                PostingLine.credit(accountB.getId(), 10000L)
        );

        PostingResult result = ledgerPostingService.post(command);

        List<JournalEntry> entries = journalEntryRepository.findByJournalTransactionId(result.journalTransactionId());
        assertThat(entries).hasSize(3);

        List<JournalEntry> accountAEntries = journalEntryRepository.findByLedgerAccountId(accountA.getId())
                .stream()
                .filter(e -> e.getJournalTransaction().getId().equals(result.journalTransactionId()))
                .toList();
        assertThat(accountAEntries).hasSize(2);
        assertThat(accountAEntries.stream().mapToLong(JournalEntry::getAmountMinor).sum()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Unbalanced posting is rejected and leaves zero committed records")
    void unbalancedPostingRejected() {
        LedgerAccount accountA = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount accountB = createSystemAccount(AccountType.PSP_CLEARING);

        long journalCountBefore = journalTransactionRepository.count();
        long entryCountBefore = journalEntryRepository.count();

        assertThatThrownBy(() -> PostJournalCommand.of(
                PostingLine.debit(accountA.getId(), 10000L),
                PostingLine.credit(accountB.getId(), 9000L)
        )).isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("is not balanced");

        assertThat(journalTransactionRepository.count()).isEqualTo(journalCountBefore);
        assertThat(journalEntryRepository.count()).isEqualTo(entryCountBefore);
    }

    @Test
    @DisplayName("Posting referencing nonexistent account is rejected and rolls back")
    void missingAccountRejected() {
        LedgerAccount accountA = createSystemAccount(AccountType.PLATFORM_RESERVE);
        UUID nonexistentAccountId = UUID.randomUUID();

        long journalCountBefore = journalTransactionRepository.count();
        long entryCountBefore = journalEntryRepository.count();

        PostJournalCommand command = PostJournalCommand.of(
                PostingLine.debit(accountA.getId(), 10000L),
                PostingLine.credit(nonexistentAccountId, 10000L)
        );

        assertThatThrownBy(() -> ledgerPostingService.post(command))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("Referenced ledger account(s) not found");

        assertThat(journalTransactionRepository.count()).isEqualTo(journalCountBefore);
        assertThat(journalEntryRepository.count()).isEqualTo(entryCountBefore);
    }

    @Test
    @DisplayName("Posting referencing a CLOSED account is rejected and rolls back")
    void closedAccountRejected() {
        LedgerAccount accountA = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount accountB = createSystemAccount(AccountType.PSP_CLEARING);
        accountB.close(Instant.now());
        ledgerAccountRepository.saveAndFlush(accountB);

        long journalCountBefore = journalTransactionRepository.count();
        long entryCountBefore = journalEntryRepository.count();

        PostJournalCommand command = PostJournalCommand.of(
                PostingLine.debit(accountA.getId(), 10000L),
                PostingLine.credit(accountB.getId(), 10000L)
        );

        assertThatThrownBy(() -> ledgerPostingService.post(command))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("Cannot post to inactive ledger account");

        assertThat(journalTransactionRepository.count()).isEqualTo(journalCountBefore);
        assertThat(journalEntryRepository.count()).isEqualTo(entryCountBefore);
    }

    @Test
    @DisplayName("Normal balance semantics do not restrict valid entry directions")
    void normalBalanceDoesNotRestrictDirection() {
        UUID userId = createTestUser();
        LedgerAccount customerAccount = ledgerAccountRepository.saveAndFlush(
                LedgerAccount.createCustomerAccount(userId)
        );
        LedgerAccount clearingAccount = createSystemAccount(AccountType.PSP_CLEARING);

        // Debit customer account (normal balance is CREDIT) and Credit PSP clearing (normal balance is DEBIT)
        PostJournalCommand command = PostJournalCommand.of(
                PostingLine.debit(customerAccount.getId(), 5000L),
                PostingLine.credit(clearingAccount.getId(), 5000L)
        );

        PostingResult result = ledgerPostingService.post(command);
        assertThat(result.journalTransactionId()).isNotNull();

        JournalTransaction txn = journalTransactionRepository.findById(result.journalTransactionId()).orElseThrow();
        assertThat(txn.getStatus()).isEqualTo(JournalStatus.POSTED);
    }

    @Test
    @DisplayName("Ledger account rows are not mutated by journal posting; no balance columns exist")
    void postingDoesNotMutateAccountBalanceColumns() {
        LedgerAccount accountA = createSystemAccount(AccountType.PLATFORM_RESERVE);
        LedgerAccount accountB = createSystemAccount(AccountType.PSP_CLEARING);

        PostJournalCommand command = PostJournalCommand.of(
                PostingLine.debit(accountA.getId(), 10000L),
                PostingLine.credit(accountB.getId(), 10000L)
        );

        ledgerPostingService.post(command);

        LedgerAccount freshA = ledgerAccountRepository.findById(accountA.getId()).orElseThrow();
        LedgerAccount freshB = ledgerAccountRepository.findById(accountB.getId()).orElseThrow();

        assertThat(freshA.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(freshB.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(freshA.getCurrency()).isEqualTo("INR");
        assertThat(freshB.getCurrency()).isEqualTo("INR");

        List<String> accountColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'ledger_accounts'",
                String.class
        );
        assertThat(accountColumns).containsExactlyInAnyOrder(
                "id", "owner_user_id", "account_type", "currency", "status", "created_at", "updated_at"
        );
        assertThat(accountColumns).doesNotContain("balance", "available_balance", "current_balance");
    }

    private LedgerAccount createSystemAccount(AccountType type) {
        LedgerAccount account = LedgerAccount.createSystemAccount(type);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private UUID createTestUser() {
        UUID id = UUID.randomUUID();
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, "posting_test." + id + "@example.com", "$2a$10$dummyHashValueForTestingPurposeOnly", "CUSTOMER", "ACTIVE", now, now
        );
        return id;
    }
}
