package com.ledgerguard.financial.api;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.fixture.PlatformFeeTestHelper;
import com.ledgerguard.funding.domain.FundingOperation;
import com.ledgerguard.funding.domain.FundingStatus;
import com.ledgerguard.funding.infrastructure.FundingOperationRepository;
import com.ledgerguard.funding.infrastructure.PspClient;
import com.ledgerguard.hold.application.HoldService;
import com.ledgerguard.identity.domain.*;
import com.ledgerguard.ledger.application.*;
import com.ledgerguard.ledger.domain.*;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.payment.application.CreatePaymentCommand;
import com.ledgerguard.payment.application.PaymentService;
import com.ledgerguard.payout.domain.Payout;
import com.ledgerguard.payout.domain.PayoutStatus;
import com.ledgerguard.payout.infrastructure.PayoutRepository;
import com.ledgerguard.refund.application.CreateRefundCommand;
import com.ledgerguard.refund.application.RefundService;
import com.ledgerguard.shared.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FinancialReadControllerIntegrationTest extends AbstractIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired LedgerAccountRepository accounts;
    @Autowired FundingOperationRepository funding;
    @Autowired PayoutRepository payouts;
    @Autowired LedgerPostingService posting;
    @Autowired HoldService holds;
    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PspClient psp;
    private MockMvc mvc;
    private User customer, otherCustomer, merchant, otherMerchant, ops;
    private LedgerAccount customerWallet, merchantWallet, otherCustomerWallet, otherMerchantWallet;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        customer = user(UserRole.CUSTOMER); otherCustomer = user(UserRole.CUSTOMER);
        merchant = user(UserRole.MERCHANT); otherMerchant = user(UserRole.MERCHANT); ops = user(UserRole.OPS);
        customerWallet = wallet(customer); merchantWallet = wallet(merchant);
        otherCustomerWallet = wallet(otherCustomer); otherMerchantWallet = wallet(otherMerchant);
        PlatformFeeTestHelper.ensureSingleActiveFeeAccount(accounts);
    }

    @Test
    void fundingIsOwnerScopedAndAmountsRemainExact() throws Exception {
        var own = newFunding(customer, customerWallet, 9007199254740995L, Instant.now());
        newFunding(otherCustomer, otherCustomerWallet, 100, Instant.now());
        var before = financialState();
        read(customer, "/api/funding").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items[0].fundingId", is(own.getId().toString())))
                .andExpect(jsonPath("$.items[0].amountMinor", is("9007199254740995")));
        read(customer, "/api/funding/" + own.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.amountMinor", is("9007199254740995")));
        read(otherCustomer, "/api/funding/" + own.getId()).andExpect(status().isNotFound()).andExpect(content().string(""));
        read(customer, "/api/funding/" + UUID.randomUUID()).andExpect(status().isNotFound());
        unchanged(before);
    }

    @ParameterizedTest
    @EnumSource(FundingStatus.class)
    void fundingReadsEveryDurableStatusWithoutMutating(FundingStatus target) throws Exception {
        var operation = newFunding(customer, customerWallet, 100, Instant.now());
        if (target != FundingStatus.CREATED) {
            operation.prepareSubmission(Instant.now().plusSeconds(3600)); funding.saveAndFlush(operation);
            switch (target) {
                case UNKNOWN -> operation.markUnknown(Instant.now(), Instant.now().plusSeconds(3600));
                case RECONCILIATION_REQUIRED -> operation.markReconciliationRequired();
                case FAILED -> operation.markFailed(Instant.now(), UUID.randomUUID());
                case SUCCEEDED -> operation.markSucceeded(UUID.randomUUID(), credit(customerWallet, 100, AccountType.PSP_CLEARING), Instant.now());
                default -> { }
            }
            funding.saveAndFlush(operation);
        }
        var before = financialState();
        read(customer, "/api/funding/" + operation.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.status", is(target.name())));
        unchanged(before);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"CUSTOMER", "MERCHANT"})
    void payoutHistoryIsOwnerScoped(UserRole role) throws Exception {
        User owner = role == UserRole.CUSTOMER ? customer : merchant;
        LedgerAccount account = role == UserRole.CUSTOMER ? customerWallet : merchantWallet;
        var own = newPayout(owner, account, 9007199254740995L, Instant.now());
        newPayout(otherCustomer, otherCustomerWallet, 100, Instant.now());
        newPayout(otherMerchant, otherMerchantWallet, 100, Instant.now());
        var before = financialState();
        read(owner, "/api/payouts").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items[0].payoutId", is(own.getId().toString())))
                .andExpect(jsonPath("$.items[0].amountMinor", is("9007199254740995")));
        read(owner, "/api/payouts/" + own.getId()).andExpect(status().isOk());
        for (User unrelated : List.of(otherCustomer, otherMerchant)) {
            read(unrelated, "/api/payouts/" + own.getId()).andExpect(status().isNotFound()).andExpect(content().string(""));
        }
        unchanged(before);
    }

    @ParameterizedTest
    @EnumSource(PayoutStatus.class)
    void payoutDetailPreservesDurableStatusAndHolds(PayoutStatus target) throws Exception {
        var payout = newPayout(customer, customerWallet, 100, Instant.now());
        if (target != PayoutStatus.CREATED) {
            payout.prepareSubmission(Instant.now().plusSeconds(3600)); payouts.saveAndFlush(payout);
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                switch (target) {
                    case UNKNOWN -> payout.markUnknown(Instant.now(), Instant.now().plusSeconds(3600));
                    case RECONCILIATION_REQUIRED -> payout.markReconciliationRequired();
                    case FAILED -> { holds.releaseHold(payout.getBalanceHoldId()); payout.markFailed(Instant.now(), UUID.randomUUID()); }
                    case SUCCEEDED -> {
                        holds.consumeHold(payout.getBalanceHoldId());
                        UUID journal = posting.post(PostJournalCommand.of(PostingLine.debit(customerWallet.getId(), 100),
                                PostingLine.credit(system(AccountType.PSP_CLEARING).getId(), 100))).journalTransactionId();
                        payout.markSucceeded(UUID.randomUUID(), journal, Instant.now());
                    }
                    default -> { }
                }
                payouts.saveAndFlush(payout);
            });
        }
        var before = financialState();
        read(customer, "/api/payouts/" + payout.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(target.name())))
                .andExpect(jsonPath("$.balanceHoldId", is(payout.getBalanceHoldId().toString())));
        unchanged(before);
    }

    @Test
    void paymentListsAndRefundDetailsAreParticipantScoped() throws Exception {
        UUID own = payment(customer, customerWallet, merchantWallet, 10000);
        payment(otherCustomer, otherCustomerWallet, otherMerchantWallet, 10000);
        var firstRefund = refunds.createRefund(new CreateRefundCommand(merchant.getId(), UUID.randomUUID().toString(), own, Money.inr(1000)));
        var lastRefund = refunds.createRefund(new CreateRefundCommand(merchant.getId(), UUID.randomUUID().toString(), own, Money.inr(2000)));
        var before = financialState();
        for (User participant : List.of(customer, merchant)) {
            read(participant, "/api/payments").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(1)))
                    .andExpect(jsonPath("$.items[0].paymentId", is(own.toString())));
            read(participant, "/api/payments/" + own + "?refundSize=1").andExpect(status().isOk())
                    .andExpect(jsonPath("$.payment.grossAmountMinor", is("10000")))
                    .andExpect(jsonPath("$.refundedAmountMinor", is("3000")))
                    .andExpect(jsonPath("$.refundableAmountMinor", is("7000")))
                    .andExpect(jsonPath("$.refunds.totalElements", is(2)))
                    .andExpect(jsonPath("$.refunds.items[0].refundId", is(lastRefund.refundId().toString())));
            read(participant, "/api/payments/" + own + "?refundSize=1&refundPage=1").andExpect(status().isOk())
                    .andExpect(jsonPath("$.refunds.items[0].refundId", is(firstRefund.refundId().toString())));
            read(participant, "/api/payments/" + own + "?refundPage=-2&refundSize=999").andExpect(status().isOk())
                    .andExpect(jsonPath("$.refunds.page", is(0))).andExpect(jsonPath("$.refunds.size", is(50)));
        }
        for (User unrelated : List.of(otherCustomer, otherMerchant)) {
            read(unrelated, "/api/payments/" + own).andExpect(status().isNotFound()).andExpect(content().string(""));
        }
        unchanged(before);
    }

    @Test
    void paymentReadSerializesLargeAmountsExactly() throws Exception {
        var id = payment(customer, customerWallet, merchantWallet, 9007199254740995L);
        read(customer, "/api/payments/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.grossAmountMinor", is("9007199254740995")))
                .andExpect(jsonPath("$.payment.feeAmountMinor", is("90071992547409")))
                .andExpect(jsonPath("$.payment.merchantNetAmountMinor", is("8917127262193586")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"funding", "payouts", "payments"})
    void paginationAndOrderingAreBounded(String domain) throws Exception {
        for (int i = 0; i < 3; i++) {
            Instant created = Instant.now().minusSeconds(10 - i);
            if (domain.equals("funding")) newFunding(customer, customerWallet, 100 + i, created);
            else if (domain.equals("payouts")) newPayout(customer, customerWallet, 100 + i, created);
            else payment(customer, customerWallet, merchantWallet, 100 + i);
        }
        String amount = domain.equals("payments") ? "grossAmountMinor" : "amountMinor";
        var before = financialState();
        read(customer, "/api/" + domain + "?size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3))).andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.items[0]." + amount, is("102")));
        read(customer, "/api/" + domain + "?size=1&page=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]." + amount, is("101")));
        read(customer, "/api/" + domain + "?size=999&page=-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(50))).andExpect(jsonPath("$.page", is(0)));
        read(customer, "/api/" + domain + "?size=0").andExpect(status().isOk()).andExpect(jsonPath("$.size", is(20)));
        read(customer, "/api/" + domain + "?page=99").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()", is(0)));
        read(customer, "/api/" + domain + "?page=wrong").andExpect(status().isBadRequest());
        unchanged(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"funding", "payouts", "payments"})
    void roleMatrixMissingIdsAndEmptyLists(String domain) throws Exception {
        var before = financialState();
        for (String path : List.of("/api/" + domain, "/api/" + domain + "/" + UUID.randomUUID())) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            read(ops, path).andExpect(status().isForbidden());
            if (domain.equals("funding")) read(merchant, path).andExpect(status().isForbidden());
        }
        read(customer, "/api/" + domain).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()", is(0)));
        read(customer, "/api/" + domain + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
        unchanged(before);
    }

    private ResultActions read(User user, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + tokens.generateAccessToken(user)));
    }
    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID(), "financial-read-" + UUID.randomUUID() + "@example.com", "$2a$10$hash", role, UserStatus.ACTIVE));
    }
    private LedgerAccount wallet(User user) {
        return accounts.saveAndFlush(user.getRole() == UserRole.CUSTOMER ? LedgerAccount.createCustomerAccount(user.getId()) : LedgerAccount.createMerchantAccount(user.getId()));
    }
    private LedgerAccount system(AccountType type) {
        return accounts.findAllByAccountType(type).stream().filter(a -> a.getStatus() == AccountStatus.ACTIVE).findFirst()
                .orElseGet(() -> accounts.saveAndFlush(LedgerAccount.createSystemAccount(type)));
    }
    private UUID credit(LedgerAccount wallet, long amount, AccountType source) {
        return posting.post(PostJournalCommand.of(PostingLine.debit(system(source).getId(), amount), PostingLine.credit(wallet.getId(), amount))).journalTransactionId();
    }
    private FundingOperation newFunding(User user, LedgerAccount wallet, long amount, Instant created) {
        return funding.saveAndFlush(new FundingOperation(UUID.randomUUID(), user.getId(), wallet.getId(), amount, "INR", created));
    }
    private Payout newPayout(User user, LedgerAccount wallet, long amount, Instant created) {
        credit(wallet, amount, AccountType.PLATFORM_RESERVE);
        var hold = holds.createHold(wallet.getId(), Money.inr(amount), Instant.now().plusSeconds(7200));
        return payouts.saveAndFlush(new Payout(UUID.randomUUID(), user.getId(), wallet.getId(), hold.getId(), amount, "INR", created));
    }
    private UUID payment(User payer, LedgerAccount source, LedgerAccount destination, long amount) {
        credit(source, amount, AccountType.PLATFORM_RESERVE);
        return payments.createPayment(new CreatePaymentCommand(payer.getId(), UUID.randomUUID().toString(), destination.getId(), Money.inr(amount))).paymentId();
    }
    private Map<String, List<String>> financialState() {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("funding_operations", "payouts", "payments", "refunds", "balance_holds",
                "journal_transactions", "journal_entries", "ledger_balance_snapshots", "idempotency_records", "outbox_events")) {
            snapshot.put(table, jdbc.queryForList("select row_to_json(t)::text from " + table + " t order by row_to_json(t)::text", String.class));
        }
        return snapshot;
    }
    private void unchanged(Map<String, List<String>> before) {
        assertThat(financialState()).isEqualTo(before);
        verifyNoInteractions(psp);
    }
}
