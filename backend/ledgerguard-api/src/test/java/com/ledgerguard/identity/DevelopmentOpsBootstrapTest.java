package com.ledgerguard.identity;

import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.identity.infrastructure.DevelopmentOpsBootstrap;
import com.ledgerguard.identity.infrastructure.OpsBootstrapProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevelopmentOpsBootstrapTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private Environment environment;

    @Mock
    private ApplicationArguments applicationArguments;

    private PasswordEncoder passwordEncoder;
    private OpsBootstrapProperties properties;
    private DevelopmentOpsBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        properties = new OpsBootstrapProperties();
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        bootstrap = new DevelopmentOpsBootstrap(properties, userRepository, passwordEncoder, environment);
    }

    @Test
    @DisplayName("Bootstrap disabled: no OPS user is created and no interaction with DB")
    void bootstrapDisabledDoesNothing() {
        properties.setEnabled(false);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        bootstrap.run(applicationArguments);

        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Enabled with valid configuration: one ACTIVE OPS user is created with BCrypt password")
    void enabledWithValidConfigCreatesActiveOpsUser() {
        properties.setEnabled(true);
        properties.setEmail("Ops.Admin@LedgerGuard.LOCAL ");
        properties.setPassword("ValidOpsPass123!");
        properties.setFullName("Platform Operations Engineer");

        when(userRepository.findByEmail("ops.admin@ledgerguard.local")).thenReturn(Optional.empty());

        bootstrap.run(applicationArguments);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());

        User created = userCaptor.getValue();
        assertThat(created.getEmail()).isEqualTo("ops.admin@ledgerguard.local");
        assertThat(created.getFullName()).isEqualTo("Platform Operations Engineer");
        assertThat(created.getRole()).isEqualTo(UserRole.OPS);
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(passwordEncoder.matches("ValidOpsPass123!", created.getPasswordHash())).isTrue();
        assertThat(created.getPasswordHash()).doesNotContain("ValidOpsPass123!");
    }

    @Test
    @DisplayName("Running provisioning twice is idempotent: existing active OPS user is not modified")
    void runningProvisioningTwiceIsIdempotent() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        String originalHash = passwordEncoder.encode("OriginalPass1234!");
        User existingOps = new User(UUID.randomUUID(), "Existing Ops", "ops@ledgerguard.local",
                originalHash, UserRole.OPS, UserStatus.ACTIVE);

        when(userRepository.findByEmail("ops@ledgerguard.local")).thenReturn(Optional.of(existingOps));

        bootstrap.run(applicationArguments);

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(existingOps.getPasswordHash()).isEqualTo(originalHash);
        assertThat(existingOps.getFullName()).isEqualTo("Existing Ops");
        assertThat(existingOps.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("Existing CUSTOMER account with configured email causes safe failure and is not promoted")
    void existingCustomerCausesSafeFailure() {
        properties.setEnabled(true);
        properties.setEmail("customer@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        User customer = new User(UUID.randomUUID(), "customer@ledgerguard.local",
                passwordEncoder.encode("CustPass1234!"), UserRole.CUSTOMER, UserStatus.ACTIVE);

        when(userRepository.findByEmail("customer@ledgerguard.local")).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered to a CUSTOMER account")
                .hasMessageContaining("Promotion to OPS is strictly forbidden")
                .hasMessageNotContaining("ValidOpsPass123!");

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(customer.getRole()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    @DisplayName("Existing MERCHANT account with configured email causes safe failure and is not promoted")
    void existingMerchantCausesSafeFailure() {
        properties.setEnabled(true);
        properties.setEmail("merchant@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        User merchant = new User(UUID.randomUUID(), "merchant@ledgerguard.local",
                passwordEncoder.encode("MerchPass1234!"), UserRole.MERCHANT, UserStatus.ACTIVE);

        when(userRepository.findByEmail("merchant@ledgerguard.local")).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered to a MERCHANT account")
                .hasMessageContaining("Promotion to OPS is strictly forbidden")
                .hasMessageNotContaining("ValidOpsPass123!");

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(merchant.getRole()).isEqualTo(UserRole.MERCHANT);
    }

    @Test
    @DisplayName("Disabled OPS account is not silently reactivated")
    void disabledOpsAccountFailsSafely() {
        properties.setEnabled(true);
        properties.setEmail("disabled.ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        User disabledOps = new User(UUID.randomUUID(), "Disabled Ops", "disabled.ops@ledgerguard.local",
                passwordEncoder.encode("OldPass1234!"), UserRole.OPS, UserStatus.DISABLED);

        when(userRepository.findByEmail("disabled.ops@ledgerguard.local")).thenReturn(Optional.of(disabledOps));

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exists but is DISABLED")
                .hasMessageContaining("Silent reactivation is strictly forbidden")
                .hasMessageNotContaining("ValidOpsPass123!");

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(disabledOps.getStatus()).isEqualTo(UserStatus.DISABLED);
    }

    @Test
    @DisplayName("Missing email when enabled throws clear exception without leaking secrets")
    void missingEmailThrowsException() {
        properties.setEnabled(true);
        properties.setEmail("");
        properties.setPassword("ValidOpsPass123!");

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email must not be blank")
                .hasMessageNotContaining("ValidOpsPass123!");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "invalid@", "@domain.local", "ops@domain", "ops @domain.local", "ops@.com"})
    @DisplayName("Invalid email formats fail fast with sanitized exception")
    void invalidEmailFormatThrowsException(String invalidEmail) {
        properties.setEnabled(true);
        properties.setEmail(invalidEmail);
        properties.setPassword("ValidOpsPass123!");

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid email address")
                .hasMessageNotContaining("ValidOpsPass123!");
    }

    @Test
    @DisplayName("Blank full name when configured throws exception")
    void blankFullNameThrowsException() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");
        properties.setFullName("   ");

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full name must not be blank")
                .hasMessageNotContaining("ValidOpsPass123!");
    }

    @Test
    @DisplayName("Full name shorter than 2 characters throws exception")
    void shortFullNameThrowsException() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");
        properties.setFullName("O");

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full name must be between 2 and 120 characters")
                .hasMessageNotContaining("ValidOpsPass123!");
    }

    @Test
    @DisplayName("Missing password when enabled throws clear exception")
    void missingPasswordThrowsException() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("  ");

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password must not be blank");
    }

    @Test
    @DisplayName("Password shorter than 12 characters is rejected")
    void shortPasswordIsRejected() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("Short1!");

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password must be at least 12 characters")
                .hasMessageNotContaining("Short1!");
    }

    @Test
    @DisplayName("Password exceeding 72 UTF-8 bytes is rejected")
    void over72BytePasswordIsRejected() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        String longPass = "A".repeat(73);
        properties.setPassword(longPass);

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum allowed BCrypt byte length of 72 UTF-8 bytes");
    }

    @Test
    @DisplayName("Concurrent startup race condition is handled idempotently when winner is ACTIVE OPS")
    void concurrentCreationRaceResolvedIdempotently() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        when(userRepository.findByEmail("ops@ledgerguard.local"))
                .thenReturn(Optional.empty()) // First check: not found
                .thenReturn(Optional.of(new User(UUID.randomUUID(), "ops@ledgerguard.local",
                        passwordEncoder.encode("ValidOpsPass123!"), UserRole.OPS, UserStatus.ACTIVE))); // In catch block: found

        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Unique index violation"));

        bootstrap.run(applicationArguments);

        verify(userRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("Concurrent race condition fails safely if winner is non-OPS")
    void concurrentRaceFailsIfWinnerIsCustomer() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        when(userRepository.findByEmail("ops@ledgerguard.local"))
                .thenReturn(Optional.empty()) // First check
                .thenReturn(Optional.of(new User(UUID.randomUUID(), "ops@ledgerguard.local",
                        passwordEncoder.encode("CustPass123!"), UserRole.CUSTOMER, UserStatus.ACTIVE))); // Winner is CUSTOMER

        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Unique index violation"));

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was created with non-OPS role CUSTOMER")
                .hasMessageContaining("Role promotion is strictly forbidden")
                .hasMessageNotContaining("ValidOpsPass123!");
    }

    @Test
    @DisplayName("Concurrent race condition fails safely if winner is DISABLED OPS")
    void concurrentRaceFailsIfWinnerIsDisabled() {
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        when(userRepository.findByEmail("ops@ledgerguard.local"))
                .thenReturn(Optional.empty()) // First check
                .thenReturn(Optional.of(new User(UUID.randomUUID(), "ops@ledgerguard.local",
                        passwordEncoder.encode("OldPass123!"), UserRole.OPS, UserStatus.DISABLED))); // Winner is DISABLED

        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Unique index violation"));

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was created with status DISABLED")
                .hasMessageContaining("Silent reactivation is strictly forbidden")
                .hasMessageNotContaining("ValidOpsPass123!");
    }

    @Test
    @DisplayName("Executing in prod profile is strictly forbidden")
    void prodProfileRefusesExecution() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly forbidden in production profile")
                .hasMessageNotContaining("ValidOpsPass123!");

        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Executing in production profile is strictly forbidden")
    void productionProfileRefusesExecution() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        properties.setEnabled(true);
        properties.setEmail("ops@ledgerguard.local");
        properties.setPassword("ValidOpsPass123!");

        assertThatThrownBy(() -> bootstrap.run(applicationArguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly forbidden in production profile")
                .hasMessageNotContaining("ValidOpsPass123!");

        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).saveAndFlush(any());
    }
}
