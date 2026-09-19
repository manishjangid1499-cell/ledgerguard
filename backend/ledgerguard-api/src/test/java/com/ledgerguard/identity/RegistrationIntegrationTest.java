package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.api.AuthController;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.shared.error.ApiErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("CUSTOMER registration succeeds with 201 Created, fullName and normalized lowercase email")
    void registerCustomerSucceeds() throws Exception {
        String testPassword = "SecurePassword1234!";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Alice Smith",
                                  "email": "Customer.Alice@Example.COM",
                                  "password": "%s",
                                  "role": "CUSTOMER"
                                }
                                """.formatted(testPassword)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.fullName", is("Alice Smith")))
                .andExpect(jsonPath("$.email", is("customer.alice@example.com")))
                .andExpect(jsonPath("$.role", is("CUSTOMER")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User user = userRepository.findByEmail("customer.alice@example.com").orElseThrow();
        assertThat(user.getFullName()).isEqualTo("Alice Smith");
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getPasswordHash()).startsWith("$2a$");
        assertThat(passwordEncoder.matches(testPassword, user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("MERCHANT registration succeeds with 201 Created and fullName")
    void registerMerchantSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Bob Merchant",
                                  "email": "merchant.bob@example.com",
                                  "password": "MerchantPassword1234!",
                                  "role": "MERCHANT"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName", is("Bob Merchant")))
                .andExpect(jsonPath("$.email", is("merchant.bob@example.com")))
                .andExpect(jsonPath("$.role", is("MERCHANT")));

        User user = userRepository.findByEmail("merchant.bob@example.com").orElseThrow();
        assertThat(user.getFullName()).isEqualTo("Bob Merchant");
    }

    @Test
    @DisplayName("Registration trims leading and trailing whitespace from fullName")
    void registerTrimsFullNameWhitespace() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "   Jane   Doe   ",
                                  "email": "jane.doe@example.com",
                                  "password": "SecurePassword1234!",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName", is("Jane   Doe")));

        User user = userRepository.findByEmail("jane.doe@example.com").orElseThrow();
        assertThat(user.getFullName()).isEqualTo("Jane   Doe");
    }

    @Test
    @DisplayName("Registration with blank fullName is rejected with 400 Bad Request")
    void registerBlankFullNameRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "    ",
                                  "email": "blank.name@example.com",
                                  "password": "SecurePassword1234!",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }

    @Test
    @DisplayName("Registration with missing fullName is rejected with 400 Bad Request")
    void registerMissingFullNameRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "missing.name@example.com",
                                  "password": "SecurePassword1234!",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }

    @Test
    @DisplayName("Registration with fullName shorter than 2 characters is rejected with 400 Bad Request")
    void registerShortFullNameRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "A",
                                  "email": "short.name@example.com",
                                  "password": "SecurePassword1234!",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }

    @Test
    @DisplayName("Registration with fullName exceeding 120 characters is rejected with 400 Bad Request")
    void registerLongFullNameRejected() throws Exception {
        String excessiveName = "A".repeat(121);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "%s",
                                  "email": "long.name@example.com",
                                  "password": "SecurePassword1234!",
                                  "role": "CUSTOMER"
                                }
                                """.formatted(excessiveName)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }

    @Test
    @DisplayName("User registered with fullName returns fullName on login, refresh, and /api/auth/me")
    void registeredUserFullNamePropagatedAcrossAuthEndpoints() throws Exception {
        String email = "charlie.brown@example.com";
        String password = "CharlieSecurePassword1234!";

        // 1. Register
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Charlie Brown",
                                  "email": "%s",
                                  "password": "%s",
                                  "role": "CUSTOMER"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName", is("Charlie Brown")));

        // 2. Login
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.fullName", is("Charlie Brown")))
                .andExpect(cookie().exists(AuthController.REFRESH_COOKIE_NAME))
                .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        org.json.JSONObject loginJson = new org.json.JSONObject(responseBody);
        String accessToken = loginJson.getString("accessToken");
        Cookie refreshCookie = loginResult.getResponse().getCookie(AuthController.REFRESH_COOKIE_NAME);
        assertThat(refreshCookie).isNotNull();

        // 3. Refresh
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.fullName", is("Charlie Brown")));

        // 4. Me
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName", is("Charlie Brown")))
                .andExpect(jsonPath("$.email", is(email)));
    }

    @Test
    @DisplayName("OPS self-registration is rejected with 400 Bad Request")
    void registerOpsIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Ops Attacker",
                                  "email": "malicious.ops@example.com",
                                  "password": "OpsPassword1234!",
                                  "role": "OPS"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)))
                .andExpect(jsonPath("$.detail", is("Registration with OPS role is not permitted.")));

        assertThat(userRepository.findByEmail("malicious.ops@example.com")).isEmpty();
    }

    @Test
    @DisplayName("Duplicate email registration is rejected with 400 Bad Request and EMAIL_ALREADY_REGISTERED")
    void duplicateEmailRegistrationRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Duplicate Person",
                                  "email": "duplicate.user@example.com",
                                  "password": "SecurePassword1234!",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Duplicate Person 2",
                                  "email": "DUPLICATE.USER@example.com",
                                  "password": "AnotherPassword1234!",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.EMAIL_ALREADY_REGISTERED)))
                .andExpect(jsonPath("$.detail", is("Email is already registered.")));
    }

    @Test
    @DisplayName("Weak/short password (< 12 characters) is rejected with 400 VALIDATION_FAILED")
    void weakPasswordRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Short Password Person",
                                  "email": "short.pwd@example.com",
                                  "password": "short",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }

    @Test
    @DisplayName("Password exceeding BCrypt safe UTF-8 byte boundary (> 72 bytes) is rejected with 400 VALIDATION_FAILED")
    void excessiveByteLengthPasswordRejected() throws Exception {
        String longPassword = "A".repeat(73); // 73 ASCII bytes > 72 bytes
        assertThat(longPassword.getBytes(StandardCharsets.UTF_8).length).isEqualTo(73);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Long Password Person",
                                  "email": "toolong.pwd@example.com",
                                  "password": "%s",
                                  "role": "CUSTOMER"
                                }
                                """.formatted(longPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }

    @Test
    @DisplayName("Multi-byte Unicode password exceeding 72 UTF-8 bytes is rejected even if String length <= 72")
    void multibyteUnicodeExceedingByteBoundaryRejected() throws Exception {
        // '€' is 3 UTF-8 bytes. 25 characters = 75 bytes (> 72 bytes) despite character length being only 25
        String unicodePassword = "€".repeat(25);
        assertThat(unicodePassword.length()).isEqualTo(25);
        assertThat(unicodePassword.getBytes(StandardCharsets.UTF_8).length).isEqualTo(75);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Unicode Password Person",
                                  "email": "unicode.pwd@example.com",
                                  "password": "%s",
                                  "role": "CUSTOMER"
                                }
                                """.formatted(unicodePassword)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }

    @Test
    @DisplayName("Malformed email is rejected with 400 VALIDATION_FAILED")
    void malformedEmailRejected() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Malformed Email Person",
                                  "email": "not-an-email",
                                  "password": "ValidPassword1234!",
                                  "role": "CUSTOMER"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode", is(ApiErrorCode.VALIDATION_FAILED)));
    }
}
