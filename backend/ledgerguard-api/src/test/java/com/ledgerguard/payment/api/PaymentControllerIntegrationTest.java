package com.ledgerguard.payment.api;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.ledger.application.LedgerPostingService;
import com.ledgerguard.ledger.application.PostJournalCommand;
import com.ledgerguard.ledger.application.PostingLine;
import com.ledgerguard.ledger.domain.AccountStatus;
import com.ledgerguard.ledger.domain.AccountType;
import com.ledgerguard.ledger.domain.LedgerAccount;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
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

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerIntegrationTest extends AbstractIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        java.util.List<LedgerAccount> feeAccounts = ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_FEES);
        for (LedgerAccount fa : feeAccounts) {
            if (fa.getStatus() == AccountStatus.ACTIVE) {
                fa.close(java.time.Instant.now());
                ledgerAccountRepository.saveAndFlush(fa);
            }
        }
        LedgerAccount canonicalFee = LedgerAccount.createSystemAccount(AccountType.PLATFORM_FEES);
        ledgerAccountRepository.saveAndFlush(canonicalFee);
    }

    @Test
    @DisplayName("POST /api/payments creates payment with 201 Created and string-serialized amounts")
    void createPaymentSuccess() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.pay." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchant = new User(UUID.randomUUID(), "merch.pay." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchant);

        LedgerAccount customerWallet = createTestWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createTestWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 100000L); // 1000 INR

        String customerToken = jwtTokenService.generateAccessToken(customer);
        String idempotencyKey = "key-pay-" + UUID.randomUUID();

        CreatePaymentRequest request = new CreatePaymentRequest(merchantWallet.getId(), 10000L); // 100 INR

        // First execution -> 201 Created
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.paymentId", notNullValue()))
                .andExpect(jsonPath("$.customerLedgerAccountId", is(customerWallet.getId().toString())))
                .andExpect(jsonPath("$.merchantLedgerAccountId", is(merchantWallet.getId().toString())))
                .andExpect(jsonPath("$.grossAmountMinor", is("10000")))
                .andExpect(jsonPath("$.feeAmountMinor", is("100")))
                .andExpect(jsonPath("$.merchantNetAmountMinor", is("9900")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.journalTransactionId", notNullValue()))
                .andExpect(jsonPath("$.replayed", is(false)));

        // Replay -> 200 OK
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                .andExpect(jsonPath("$.grossAmountMinor", is("10000")))
                .andExpect(jsonPath("$.feeAmountMinor", is("100")))
                .andExpect(jsonPath("$.merchantNetAmountMinor", is("9900")))
                .andExpect(jsonPath("$.replayed", is(true)));
    }

    @Test
    @DisplayName("POST /api/payments serializes amounts above Number.MAX_SAFE_INTEGER without precision loss")
    void largeAmountStringSerialization() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.large." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchant = new User(UUID.randomUUID(), "merch.large." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchant);

        LedgerAccount customerWallet = createTestWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createTestWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        long largeGross = 9007199254740995L; // > Number.MAX_SAFE_INTEGER
        fundWallet(customerWallet.getId(), largeGross);

        String customerToken = jwtTokenService.generateAccessToken(customer);
        String idempotencyKey = "key-large-" + UUID.randomUUID();

        CreatePaymentRequest request = new CreatePaymentRequest(merchantWallet.getId(), largeGross);

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grossAmountMinor", is("9007199254740995")))
                .andExpect(jsonPath("$.feeAmountMinor", is("90071992547409")))
                .andExpect(jsonPath("$.merchantNetAmountMinor", is(String.valueOf(largeGross - 90071992547409L))));
    }

    @Test
    @DisplayName("POST /api/payments rejects invalid input (missing/blank idempotency key, non-positive amount, self-payment)")
    void invalidInputRejections() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.inv." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchant = new User(UUID.randomUUID(), "merch.inv." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchant);

        LedgerAccount customerWallet = createTestWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createTestWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);
        String customerToken = jwtTokenService.generateAccessToken(customer);

        // Missing Idempotency-Key
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(merchantWallet.getId(), 10000L))))
                .andExpect(status().isBadRequest());

        // Blank Idempotency-Key
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(merchantWallet.getId(), 10000L))))
                .andExpect(status().isBadRequest());

        // 129-character Idempotency-Key -> 400 Bad Request
        String key129 = "k".repeat(129);
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", key129)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(merchantWallet.getId(), 10000L))))
                .andExpect(status().isBadRequest());

        // 128-character Idempotency-Key -> 201 Created
        String key128 = "k".repeat(128);
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", key128)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(merchantWallet.getId(), 10000L))))
                .andExpect(status().isCreated());

        // Non-positive amount
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "key-zero-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(merchantWallet.getId(), 0L))))
                .andExpect(status().isBadRequest());

        // Self payment
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "key-self-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(customerWallet.getId(), 10000L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/payments returns 404 for missing merchant")
    void missingMerchantReturns404() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.404." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(customer);
        createTestWallet(customer.getId(), AccountType.CUSTOMER);
        getOrCreatePlatformFeeAccount();

        String customerToken = jwtTokenService.generateAccessToken(customer);

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "key-404-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(UUID.randomUUID(), 10000L))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/payments returns 409 for insufficient funds and idempotency conflict")
    void conflictResponses() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.conf." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchant = new User(UUID.randomUUID(), "merch.conf." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchant);

        LedgerAccount customerWallet = createTestWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createTestWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 5000L); // Only 5000
        String customerToken = jwtTokenService.generateAccessToken(customer);

        // Insufficient funds -> 409
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "key-insuf-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(merchantWallet.getId(), 10000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INSUFFICIENT_FUNDS")));

        // Fund wallet to complete first payment
        fundWallet(customerWallet.getId(), 50000L);
        String key = "key-conflict-test-" + UUID.randomUUID();
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(merchantWallet.getId(), 10000L))))
                .andExpect(status().isCreated());

        // Idempotency Conflict -> 409
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(merchantWallet.getId(), 20000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("IDEMPOTENCY_CONFLICT")));
    }

    @Test
    @DisplayName("POST /api/payments enforces authorization matrix (CUSTOMER allowed, MERCHANT/OPS 403, unauthenticated 401)")
    void authorizationMatrix() throws Exception {
        User merchant = new User(UUID.randomUUID(), "merch.auth." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        User ops = new User(UUID.randomUUID(), "ops.auth." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(merchant);
        userRepository.save(ops);

        LedgerAccount merchantWallet = createTestWallet(merchant.getId(), AccountType.MERCHANT);

        String merchantToken = jwtTokenService.generateAccessToken(merchant);
        String opsToken = jwtTokenService.generateAccessToken(ops);

        CreatePaymentRequest request = new CreatePaymentRequest(merchantWallet.getId(), 10000L);

        // 1. Unauthenticated -> 401
        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "key-unauth-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        // 2. MERCHANT -> 403
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", "key-merch-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 3. OPS -> 403
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + opsToken)
                        .header("Idempotency-Key", "key-ops-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    private LedgerAccount createTestWallet(UUID ownerUserId, AccountType type) {
        LedgerAccount account = (type == AccountType.CUSTOMER)
                ? LedgerAccount.createCustomerAccount(ownerUserId)
                : LedgerAccount.createMerchantAccount(ownerUserId);
        return ledgerAccountRepository.saveAndFlush(account);
    }

    private LedgerAccount getOrCreatePlatformFeeAccount() {
        return ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_FEES).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE && "INR".equals(a.getCurrency()) && a.getOwnerUserId() == null)
                .findFirst()
                .orElseGet(() -> {
                    LedgerAccount feeAccount = LedgerAccount.createSystemAccount(AccountType.PLATFORM_FEES);
                    return ledgerAccountRepository.saveAndFlush(feeAccount);
                });
    }

    private void fundWallet(UUID walletAccountId, long amountMinor) {
        LedgerAccount reserve = ledgerAccountRepository.findAllByAccountType(AccountType.PLATFORM_RESERVE).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE && "INR".equals(a.getCurrency()) && a.getOwnerUserId() == null)
                .findFirst()
                .orElseGet(() -> {
                    LedgerAccount reserveAcc = LedgerAccount.createSystemAccount(AccountType.PLATFORM_RESERVE);
                    return ledgerAccountRepository.saveAndFlush(reserveAcc);
                });

        ledgerPostingService.post(PostJournalCommand.of(
                PostingLine.debit(reserve.getId(), amountMinor),
                PostingLine.credit(walletAccountId, amountMinor)
        ));
    }
}
