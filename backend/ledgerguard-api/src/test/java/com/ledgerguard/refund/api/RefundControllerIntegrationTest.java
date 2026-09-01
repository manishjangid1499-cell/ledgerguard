package com.ledgerguard.refund.api;

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
import com.ledgerguard.ledger.domain.Money;
import com.ledgerguard.ledger.infrastructure.LedgerAccountRepository;
import com.ledgerguard.payment.application.CreatePaymentCommand;
import com.ledgerguard.payment.application.PaymentResult;
import com.ledgerguard.payment.application.PaymentService;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefundControllerIntegrationTest extends AbstractIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("POST /api/payments/{paymentId}/refund creates refund and returns 201 on first execution, 200 on replay")
    void createRefundAndReplay() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.api." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchant = new User(UUID.randomUUID(), "merch.api." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchant);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-api-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        String merchantToken = jwtTokenService.generateAccessToken(merchant);
        String idempotencyKey = "ref-api-key-" + UUID.randomUUID();
        CreateRefundRequest request = new CreateRefundRequest(2500L);

        // 1. First execution -> 201 Created
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId", is(payment.paymentId().toString())))
                .andExpect(jsonPath("$.refundAmountMinor", is("2500")))
                .andExpect(jsonPath("$.merchantDebitAmountMinor", is("2475")))
                .andExpect(jsonPath("$.feeDebitAmountMinor", is("25")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.replayed", is(false)));

        // 2. Idempotent replay -> 200 OK
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId", is(payment.paymentId().toString())))
                .andExpect(jsonPath("$.refundAmountMinor", is("2500")))
                .andExpect(jsonPath("$.replayed", is(true)));
    }

    @Test
    @DisplayName("POST /api/payments/{paymentId}/refund handles high-precision amounts (> Number.MAX_SAFE_INTEGER) as decimal strings")
    void highPrecisionSerialization() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.prec." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchant = new User(UUID.randomUUID(), "merch.prec." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchant);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        long largeGross = 9007199254740995L;
        fundWallet(customerWallet.getId(), largeGross);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-prec-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(largeGross, "INR")
        ));

        String merchantToken = jwtTokenService.generateAccessToken(merchant);
        String idempotencyKey = "ref-prec-key-" + UUID.randomUUID();

        long halfRefund = largeGross / 2;
        CreateRefundRequest request = new CreateRefundRequest(halfRefund);

        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundAmountMinor", is(String.valueOf(halfRefund))));
    }

    @Test
    @DisplayName("POST /api/payments/{paymentId}/refund rejects invalid input (missing/blank/oversized key, non-positive amount)")
    void invalidInputRejections() throws Exception {
        User merchant = new User(UUID.randomUUID(), "merch.inv." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(merchant);
        String merchantToken = jwtTokenService.generateAccessToken(merchant);
        UUID paymentId = UUID.randomUUID();

        // Missing Idempotency-Key
        mockMvc.perform(post("/api/payments/{paymentId}/refund", paymentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(1000L))))
                .andExpect(status().isBadRequest());

        // Blank Idempotency-Key
        mockMvc.perform(post("/api/payments/{paymentId}/refund", paymentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(1000L))))
                .andExpect(status().isBadRequest());

        // 129-character Idempotency-Key -> 400
        String key129 = "k".repeat(129);
        mockMvc.perform(post("/api/payments/{paymentId}/refund", paymentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", key129)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(1000L))))
                .andExpect(status().isBadRequest());

        // Non-positive amount -> 400
        mockMvc.perform(post("/api/payments/{paymentId}/refund", paymentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", "key-zero-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(0L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/payments/{paymentId}/refund enforces authorization matrix")
    void authorizationMatrix() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.auth." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchant = new User(UUID.randomUUID(), "merch.auth." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        User ops = new User(UUID.randomUUID(), "ops.auth." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchant);
        userRepository.save(ops);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-auth-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        String customerToken = jwtTokenService.generateAccessToken(customer);
        String opsToken = jwtTokenService.generateAccessToken(ops);
        CreateRefundRequest request = new CreateRefundRequest(2000L);

        // 1. Unauthenticated -> 401
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Idempotency-Key", "key-unauth-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        // 2. CUSTOMER -> 403
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "key-cust-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 3. OPS -> 403
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + opsToken)
                        .header("Idempotency-Key", "key-ops-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/payments/{paymentId}/refund returns 404 for unowned payment or missing payment")
    void unownedOrMissingPaymentReturns404() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.own." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchantA = new User(UUID.randomUUID(), "merch.own.a." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        User merchantB = new User(UUID.randomUUID(), "merch.own.b." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchantA);
        userRepository.save(merchantB);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantAWallet = createWallet(merchantA.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-own-" + UUID.randomUUID(),
                merchantAWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        String merchantBToken = jwtTokenService.generateAccessToken(merchantB);

        // Merchant B tries to refund Merchant A's payment -> 404
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + merchantBToken)
                        .header("Idempotency-Key", "key-unown-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(2000L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("RESOURCE_NOT_FOUND")));

        // Missing payment -> 404
        mockMvc.perform(post("/api/payments/{paymentId}/refund", UUID.randomUUID())
                        .header("Authorization", "Bearer " + merchantBToken)
                        .header("Idempotency-Key", "key-miss-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(2000L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("RESOURCE_NOT_FOUND")));
    }

    @Test
    @DisplayName("POST /api/payments/{paymentId}/refund returns 409 for over-refund and idempotency conflict")
    void conflictResponses() throws Exception {
        User customer = new User(UUID.randomUUID(), "cust.err." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User merchant = new User(UUID.randomUUID(), "merch.err." + UUID.randomUUID() + "@example.com", "$2a$10$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        userRepository.save(customer);
        userRepository.save(merchant);

        LedgerAccount customerWallet = createWallet(customer.getId(), AccountType.CUSTOMER);
        LedgerAccount merchantWallet = createWallet(merchant.getId(), AccountType.MERCHANT);
        getOrCreatePlatformFeeAccount();

        fundWallet(customerWallet.getId(), 50000L);

        PaymentResult payment = paymentService.createPayment(new CreatePaymentCommand(
                customer.getId(),
                "pay-err-" + UUID.randomUUID(),
                merchantWallet.getId(),
                Money.ofMinor(10000L, "INR")
        ));

        String merchantToken = jwtTokenService.generateAccessToken(merchant);

        // 1. Over-refund -> 409 REFUND_LIMIT_EXCEEDED
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", "key-over-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(15000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("REFUND_LIMIT_EXCEEDED")));

        // 2. Successful refund
        String key = "key-conf-test-" + UUID.randomUUID();
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(2000L))))
                .andExpect(status().isCreated());

        // 3. Idempotency Conflict -> 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(post("/api/payments/{paymentId}/refund", payment.paymentId())
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(3000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("IDEMPOTENCY_CONFLICT")));
    }

    private LedgerAccount createWallet(UUID ownerUserId, AccountType type) {
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
