package com.ledgerguard.identity;

import com.ledgerguard.AbstractIntegrationTest;
import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.shared.error.ApiErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    @DisplayName("CUSTOMER registration succeeds with 201 Created and normalized lowercase email")
    void registerCustomerSucceeds() throws Exception {
        String testPassword = "SecurePassword1234!";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "Customer.Alice@Example.COM",
                                  "password": "%s",
                                  "role": "CUSTOMER"
                                }
                                """.formatted(testPassword)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.email", is("customer.alice@example.com")))
                .andExpect(jsonPath("$.role", is("CUSTOMER")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User user = userRepository.findByEmail("customer.alice@example.com").orElseThrow();
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getPasswordHash()).startsWith("$2a$");
        assertThat(passwordEncoder.matches(testPassword, user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("MERCHANT registration succeeds with 201 Created")
    void registerMerchantSucceeds() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "merchant.bob@example.com",
                                  "password": "MerchantPassword1234!",
                                  "role": "MERCHANT"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email", is("merchant.bob@example.com")))
                .andExpect(jsonPath("$.role", is("MERCHANT")));
    }

    @Test
    @DisplayName("OPS self-registration is rejected with 400 Bad Request")
    void registerOpsIsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
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
