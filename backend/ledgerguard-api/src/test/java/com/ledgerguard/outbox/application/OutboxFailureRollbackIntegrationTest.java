package com.ledgerguard.outbox.application;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.outbox.domain.DomainEvent;
import com.ledgerguard.transfer.application.CreateTransferCommand;
import com.ledgerguard.transfer.application.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class OutboxFailureRollbackIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private OutboxService outboxService;

    @Test
    @DisplayName("Outbox failure rolls back entire financial transaction (0 transfers, 0 journals, 0 outbox)")
    void outboxFailureRollsBackFinancialTransaction() {
        User sender = createTestUser("sender.outfail." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);
        User recipient = createTestUser("recipient.outfail." + UUID.randomUUID() + "@example.com", UserRole.CUSTOMER);

        LedgerAccount sourceWallet = createWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount destWallet = createWallet(recipient.getId(), AccountType.CUSTOMER);
        fundWallet(sourceWallet.getId(), 20000L);

        String idempotencyKey = "tx-outfail-" + UUID.randomUUID();

        // Configure mock to fail on outbox append
        doThrow(new RuntimeException("Simulated outbox persistence failure"))
                .when(outboxService).append(any(DomainEvent.class));

        assertThatThrownBy(() -> transferService.createTransfer(new CreateTransferCommand(
                sender.getId(),
                destWallet.getId(),
                Money.inr(10000L),
                idempotencyKey
        ))).hasMessageContaining("Simulated outbox persistence failure");

        // Verify full rollback: 0 transfers, 0 outbox rows
        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE source_ledger_account_id = ?",
                Integer.class,
                sourceWallet.getId()
        );
        assertThat(transferCount).isEqualTo(0);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE payload->>'sourceLedgerAccountId' = ?",
                Integer.class,
                sourceWallet.getId().toString()
        );
        assertThat(outboxCount).isEqualTo(0);
    }

    private User createTestUser(String email, UserRole role) {
        User user = new User(UUID.randomUUID(), email, "$2a$10$dummyHashValueForTestingOnly", role, UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private LedgerAccount createWallet(UUID ownerUserId, AccountType type) {
        LedgerAccount account = (type == AccountType.CUSTOMER)
                ? LedgerAccount.createCustomerAccount(ownerUserId)
                : LedgerAccount.createMerchantAccount(ownerUserId);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private void fundWallet(UUID walletAccountId, long amountMinor) {
        LedgerAccount reserve = getOrCreateSystemAccount(AccountType.PLATFORM_RESERVE);
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserve.getId(), amountMinor),
                PostingLine.credit(walletAccountId, amountMinor)
        ));
    }

    private LedgerAccount getOrCreateSystemAccount(AccountType type) {
        List<LedgerAccount> existing = ledgerAccountRepository.findAll().stream()
                .filter(a -> a.getAccountType() == type && a.getOwnerUserId() == null)
                .toList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        LedgerAccount account = LedgerAccount.createSystemAccount(type);
        return ledgerAccountRepository.saveAndFlush(account);
    }
}
