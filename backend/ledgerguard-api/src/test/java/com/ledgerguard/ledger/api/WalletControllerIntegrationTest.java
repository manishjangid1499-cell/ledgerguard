package com.ledgerguard.ledger.api;

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
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.ledger.infrastructure.LedgerBalanceSnapshotRepository;
import com.ledgerguard.shared.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerBalanceSnapshotRepository ledgerBalanceSnapshotRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("CUSTOMER can retrieve own wallet with posted balance string")
    void customerCanRetrieveOwnWallet() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.wallet." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);
        LedgerAccount wallet = createTestWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(wallet.getId(), 75000L);

        String token = jwtTokenService.generateAccessToken(customer);

        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ledgerAccountId", is(wallet.getId().toString())))
                .andExpect(jsonPath("$.accountType", is("CUSTOMER")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.balanceMinor", is("75000")));
    }

    @Test
    @DisplayName("MERCHANT can retrieve own wallet with posted balance string")
    void merchantCanRetrieveOwnWallet() throws Exception {
        User merchant = new User(UUID.randomUUID(), "merch.wallet." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(merchant);
        LedgerAccount wallet = createTestWallet(merchant.getId(), AccountType.MERCHANT);
        fundWallet(wallet.getId(), 150000L);

        String token = jwtTokenService.generateAccessToken(merchant);

        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ledgerAccountId", is(wallet.getId().toString())))
                .andExpect(jsonPath("$.accountType", is("MERCHANT")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.balanceMinor", is("150000")));
    }

    @Test
    @DisplayName("OPS role is forbidden from accessing user wallet endpoint (HTTP 403)")
    void opsRoleIsForbiddenFromWallet() throws Exception {
        User ops = new User(UUID.randomUUID(), "ops.wallet." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(ops);
        String token = jwtTokenService.generateAccessToken(ops);

        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request to wallet returns HTTP 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/wallets/me")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Financial response balanceMinor serialization is precision-safe for values exceeding JS MAX_SAFE_INTEGER")
    void balanceMinorSerializationIsPrecisionSafeForLargeValues() throws Exception {
        User customer = new User(UUID.randomUUID(), "large.wallet." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);
        LedgerAccount wallet = createTestWallet(customer.getId(), AccountType.CUSTOMER);

        // Fund with amount exceeding JavaScript Number.MAX_SAFE_INTEGER (9,007,199,254,740,991)
        long largeAmount = 9007199254740995L;
        fundWallet(wallet.getId(), largeAmount);

        String token = jwtTokenService.generateAccessToken(customer);

        mockMvc.perform(get("/api/wallets/me")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor", is("9007199254740995")));
    }

    private void fundWallet(UUID walletAccountId, long amountMinor) {
        LedgerAccount reserve = createSystemAccount(AccountType.PLATFORM_RESERVE);
        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserve.getId(), amountMinor),
                PostingLine.credit(walletAccountId, amountMinor)
        ));
    }

    private LedgerAccount createTestWallet(UUID ownerUserId, AccountType type) {
        LedgerAccount account = (type == AccountType.CUSTOMER)
                ? LedgerAccount.createCustomerAccount(ownerUserId)
                : LedgerAccount.createMerchantAccount(ownerUserId);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private LedgerAccount createSystemAccount(AccountType type) {
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
