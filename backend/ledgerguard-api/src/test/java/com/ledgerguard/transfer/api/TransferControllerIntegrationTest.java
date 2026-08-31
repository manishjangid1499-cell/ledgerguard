package com.ledgerguard.transfer.api;

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
import com.ledgerguard.shared.error.ApiErrorCode;
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
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private JwtTokenService jwtTokenService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("Unauthenticated request to POST /api/transfers is rejected with HTTP 401")
    void unauthenticatedRejected401() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(UUID.randomUUID(), 10000L);

        mockMvc.perform(post("/api/transfers")
                        .header("Idempotency-Key", "key-unauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.AUTHENTICATION_REQUIRED)));
    }

    @Test
    @DisplayName("OPS role cannot execute transfers and receives HTTP 403")
    void opsRoleForbidden403() throws Exception {
        User opsUser = new User(UUID.randomUUID(), "ops." + UUID.randomUUID() + "@ledgerguard.internal", "$2a$hash", UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(opsUser);
        String opsToken = jwtTokenService.generateAccessToken(opsUser);

        CreateTransferRequest request = new CreateTransferRequest(UUID.randomUUID(), 10000L);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + opsToken)
                        .header("Idempotency-Key", "key-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.ACCESS_DENIED)));
    }

    @Test
    @DisplayName("CUSTOMER can execute transfer: returns HTTP 201 on first execution and HTTP 200 on replay")
    void customerTransferAndReplay() throws Exception {
        User sender = new User(UUID.randomUUID(), "cust.sender." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User receiver = new User(UUID.randomUUID(), "cust.receiver." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        userRepository.save(receiver);

        LedgerAccount senderWallet = createTestWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiver.getId(), AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 50000L);

        String senderToken = jwtTokenService.generateAccessToken(sender);
        String idempotencyKey = "key-cust-trf-" + UUID.randomUUID();

        CreateTransferRequest request = new CreateTransferRequest(receiverWallet.getId(), 15000L);

        // 1. First execution -> HTTP 201 Created
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transferId", notNullValue()))
                .andExpect(jsonPath("$.sourceLedgerAccountId", is(senderWallet.getId().toString())))
                .andExpect(jsonPath("$.destinationLedgerAccountId", is(receiverWallet.getId().toString())))
                .andExpect(jsonPath("$.amountMinor", is("15000")))
                .andExpect(jsonPath("$.currency", is("INR")))
                .andExpect(jsonPath("$.journalTransactionId", notNullValue()))
                .andExpect(jsonPath("$.replayed", is(false)));

        // 2. Second execution (replay) -> HTTP 200 OK
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transferId", notNullValue()))
                .andExpect(jsonPath("$.sourceLedgerAccountId", is(senderWallet.getId().toString())))
                .andExpect(jsonPath("$.destinationLedgerAccountId", is(receiverWallet.getId().toString())))
                .andExpect(jsonPath("$.amountMinor", is("15000")))
                .andExpect(jsonPath("$.replayed", is(true)));
    }

    @Test
    @DisplayName("MERCHANT can execute transfer returning HTTP 201")
    void merchantTransferSucceeds() throws Exception {
        User merchant = new User(UUID.randomUUID(), "merch." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.MERCHANT, UserStatus.ACTIVE);
        User customer = new User(UUID.randomUUID(), "cust." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(merchant);
        userRepository.save(customer);

        LedgerAccount merchantWallet = createTestWallet(merchant.getId(), AccountType.MERCHANT);
        LedgerAccount customerWallet = createTestWallet(customer.getId(), AccountType.CUSTOMER);
        fundWallet(merchantWallet.getId(), 100000L);

        String merchantToken = jwtTokenService.generateAccessToken(merchant);
        CreateTransferRequest request = new CreateTransferRequest(customerWallet.getId(), 25000L);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + merchantToken)
                        .header("Idempotency-Key", "key-merch-trf-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amountMinor", is("25000")))
                .andExpect(jsonPath("$.replayed", is(false)));
    }

    @Test
    @DisplayName("Same Idempotency-Key with modified amount returns HTTP 409 Conflict")
    void sameKeyModifiedPayloadReturns409() throws Exception {
        User sender = new User(UUID.randomUUID(), "conflict.sender." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User receiver = new User(UUID.randomUUID(), "conflict.receiver." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        userRepository.save(receiver);

        LedgerAccount senderWallet = createTestWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiver.getId(), AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 50000L);

        String senderToken = jwtTokenService.generateAccessToken(sender);
        String idempotencyKey = "key-conflict-" + UUID.randomUUID();

        // First transfer: 10,000
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTransferRequest(receiverWallet.getId(), 10000L))))
                .andExpect(status().isCreated());

        // Same key, different amount (20,000) -> 409 Conflict
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTransferRequest(receiverWallet.getId(), 20000L))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.IDEMPOTENCY_CONFLICT)));
    }

    @Test
    @DisplayName("Missing or blank Idempotency-Key header returns HTTP 400 Bad Request")
    void missingIdempotencyKeyReturns400() throws Exception {
        User sender = new User(UUID.randomUUID(), "hdr." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        String senderToken = jwtTokenService.generateAccessToken(sender);

        CreateTransferRequest request = new CreateTransferRequest(UUID.randomUUID(), 10000L);

        // Missing header
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_TRANSFER)));

        // Blank header
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_TRANSFER)));
    }

    @Test
    @DisplayName("Invalid amount (zero or negative) returns HTTP 400 Bad Request")
    void invalidAmountReturns400() throws Exception {
        User sender = new User(UUID.randomUUID(), "amt." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        String senderToken = jwtTokenService.generateAccessToken(sender);

        // Negative amount
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", "key-neg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTransferRequest(UUID.randomUUID(), -100L))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));

        // Zero amount
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", "key-zero")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTransferRequest(UUID.randomUUID(), 0L))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }

    @Test
    @DisplayName("Nonexistent destination account returns HTTP 404 Not Found")
    void missingDestinationReturns404() throws Exception {
        User sender = new User(UUID.randomUUID(), "notfound." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        createTestWallet(sender.getId(), AccountType.CUSTOMER);
        String senderToken = jwtTokenService.generateAccessToken(sender);

        CreateTransferRequest request = new CreateTransferRequest(UUID.randomUUID(), 10000L);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", "key-notfound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.RESOURCE_NOT_FOUND)));
    }

    @Test
    @DisplayName("Idempotency-Key header over 128 characters returns HTTP 400 Bad Request")
    void idempotencyKeyOver128CharsReturns400() throws Exception {
        User sender = new User(UUID.randomUUID(), "longkey." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        String senderToken = jwtTokenService.generateAccessToken(sender);

        String tooLongKey = "a".repeat(129);
        CreateTransferRequest request = new CreateTransferRequest(UUID.randomUUID(), 10000L);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", tooLongKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INVALID_TRANSFER)));
    }

    @Test
    @DisplayName("Case-sensitive Idempotency-Keys are treated as distinct independent requests")
    void caseSensitiveIdempotencyKeysAreDistinct() throws Exception {
        User sender = new User(UUID.randomUUID(), "case." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User receiver = new User(UUID.randomUUID(), "case.rx." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        userRepository.save(receiver);
        LedgerAccount senderWallet = createTestWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiver.getId(), AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 50000L);
        String senderToken = jwtTokenService.generateAccessToken(sender);

        CreateTransferRequest request = new CreateTransferRequest(receiverWallet.getId(), 10000L);

        // First request with uppercase key
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", "KEY-CASE-ABC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed", is(false)));

        // Second request with lowercase key (distinct request, not replay)
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", "key-case-abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed", is(false)));
    }

    @Test
    @DisplayName("Transfer with insufficient funds returns HTTP 409 Conflict with INSUFFICIENT_FUNDS ProblemDetail")
    void insufficientFundsReturns409() throws Exception {
        User sender = new User(UUID.randomUUID(), "insufficient." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User receiver = new User(UUID.randomUUID(), "insufficient.rx." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        userRepository.save(receiver);
        LedgerAccount senderWallet = createTestWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiver.getId(), AccountType.CUSTOMER);
        fundWallet(senderWallet.getId(), 2000L); // Sender only has 2,000 INR
        String senderToken = jwtTokenService.generateAccessToken(sender);

        CreateTransferRequest request = new CreateTransferRequest(receiverWallet.getId(), 5000L); // Attempt 5,000 INR

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", "key-insufficient-409")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.INSUFFICIENT_FUNDS)))
                .andExpect(jsonPath("$.title", is("Insufficient funds")))
                .andExpect(jsonPath("$.detail", is("Insufficient funds for this transfer.")));
    }

    @Test
    @DisplayName("GET /api/transfers returns paginated transfers where user is source or destination")
    void getTransfersReturnsUserTransfers() throws Exception {
        User userA = new User(UUID.randomUUID(), "list.a." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User userB = new User(UUID.randomUUID(), "list.b." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User userC = new User(UUID.randomUUID(), "list.c." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(userA);
        userRepository.save(userB);
        userRepository.save(userC);

        LedgerAccount walletA = createTestWallet(userA.getId(), AccountType.CUSTOMER);
        LedgerAccount walletB = createTestWallet(userB.getId(), AccountType.CUSTOMER);
        LedgerAccount walletC = createTestWallet(userC.getId(), AccountType.CUSTOMER);

        fundWallet(walletA.getId(), 50000L);
        fundWallet(walletB.getId(), 50000L);

        String tokenA = jwtTokenService.generateAccessToken(userA);
        String tokenB = jwtTokenService.generateAccessToken(userB);

        // A -> B (outgoing for A, incoming for B)
        mockMvc.perform(post("/api/transfers")
                .header("Authorization", "Bearer " + tokenA)
                .header("Idempotency-Key", "key-list-1-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTransferRequest(walletB.getId(), 10000L))))
                .andExpect(status().isCreated());

        // B -> A (incoming for A, outgoing for B)
        mockMvc.perform(post("/api/transfers")
                .header("Authorization", "Bearer " + tokenB)
                .header("Idempotency-Key", "key-list-2-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTransferRequest(walletA.getId(), 5000L))))
                .andExpect(status().isCreated());

        // B -> C (unrelated to A)
        mockMvc.perform(post("/api/transfers")
                .header("Authorization", "Bearer " + tokenB)
                .header("Idempotency-Key", "key-list-3-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTransferRequest(walletC.getId(), 2000L))))
                .andExpect(status().isCreated());

        // Query user A's transfers -> should see exactly 2 transfers (A->B and B->A, newest first)
        mockMvc.perform(get("/api/transfers?page=0&size=20")
                        .header("Authorization", "Bearer " + tokenA)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.items[0].direction", is("INCOMING")))
                .andExpect(jsonPath("$.items[0].amountMinor", is("5000")))
                .andExpect(jsonPath("$.items[1].direction", is("OUTGOING")))
                .andExpect(jsonPath("$.items[1].amountMinor", is("10000")));
    }

    @Test
    @DisplayName("GET /api/transfers/{id} returns transfer detail and journal inspector for authorized participants")
    void getTransferDetailReturnsJournalInspector() throws Exception {
        User sender = new User(UUID.randomUUID(), "detail.s." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User receiver = new User(UUID.randomUUID(), "detail.r." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        User unrelated = new User(UUID.randomUUID(), "detail.u." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(sender);
        userRepository.save(receiver);
        userRepository.save(unrelated);

        LedgerAccount senderWallet = createTestWallet(sender.getId(), AccountType.CUSTOMER);
        LedgerAccount receiverWallet = createTestWallet(receiver.getId(), AccountType.CUSTOMER);
        createTestWallet(unrelated.getId(), AccountType.CUSTOMER);

        fundWallet(senderWallet.getId(), 50000L);
        String senderToken = jwtTokenService.generateAccessToken(sender);
        String receiverToken = jwtTokenService.generateAccessToken(receiver);
        String unrelatedToken = jwtTokenService.generateAccessToken(unrelated);

        String createResp = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + senderToken)
                        .header("Idempotency-Key", "key-detail-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTransferRequest(receiverWallet.getId(), 12000L))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        TransferResponse transfer = objectMapper.readValue(createResp, TransferResponse.class);
        UUID transferId = transfer.transferId();

        // 1. Sender inspects transfer detail
        mockMvc.perform(get("/api/transfers/{id}", transferId)
                        .header("Authorization", "Bearer " + senderToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId", is(transferId.toString())))
                .andExpect(jsonPath("$.direction", is("OUTGOING")))
                .andExpect(jsonPath("$.amountMinor", is("12000")))
                .andExpect(jsonPath("$.journal.status", is("POSTED")))
                .andExpect(jsonPath("$.journal.entries.length()", is(2)))
                .andExpect(jsonPath("$.journal.entries[0].direction", notNullValue()))
                .andExpect(jsonPath("$.journal.entries[0].amountMinor", is("12000")));

        // 2. Receiver inspects transfer detail
        mockMvc.perform(get("/api/transfers/{id}", transferId)
                        .header("Authorization", "Bearer " + receiverToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId", is(transferId.toString())))
                .andExpect(jsonPath("$.direction", is("INCOMING")))
                .andExpect(jsonPath("$.amountMinor", is("12000")));

        // 3. Unrelated user attempts to inspect transfer detail -> 404 Not Found
        mockMvc.perform(get("/api/transfers/{id}", transferId)
                        .header("Authorization", "Bearer " + unrelatedToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/transfers normalizes negative pagination and caps oversized size at 50")
    void paginationLimitsAndNormalization() throws Exception {
        User user = new User(UUID.randomUUID(), "page.norm." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        userRepository.save(user);
        createTestWallet(user.getId(), AccountType.CUSTOMER);
        String token = jwtTokenService.generateAccessToken(user);

        // Negative page & oversized size
        mockMvc.perform(get("/api/transfers?page=-5&size=100")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(50)));
    }

    @Test
    @DisplayName("GET /api/transfers and GET /api/transfers/{id} reject OPS role with 403 and unauthenticated with 401")
    void transferEndpointsAuthorizationMatrix() throws Exception {
        User ops = new User(UUID.randomUUID(), "ops.trf." + UUID.randomUUID() + "@example.com", "$2a$hash", UserRole.OPS, UserStatus.ACTIVE);
        userRepository.save(ops);
        String opsToken = jwtTokenService.generateAccessToken(ops);

        // OPS -> 403
        mockMvc.perform(get("/api/transfers")
                        .header("Authorization", "Bearer " + opsToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/transfers/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + opsToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // Unauthenticated -> 401
        mockMvc.perform(get("/api/transfers")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/transfers/{id}", UUID.randomUUID())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
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
