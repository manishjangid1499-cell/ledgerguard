package com.ledgerguard.identity;

import com.ledgerguard.identity.domain.User;
import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.domain.UserRole;
import com.ledgerguard.identity.domain.UserStatus;
import com.ledgerguard.identity.infrastructure.ProductionOpsProvisioningJob;
import com.ledgerguard.identity.infrastructure.ProductionOpsProvisioningProperties;
import com.ledgerguard.identity.infrastructure.ProductionOpsProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProductionOpsProvisioningJob Unit Tests")
class ProductionOpsProvisioningJobTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final Environment environment = mock(Environment.class);

    @BeforeEach
    void setUp() {
        when(applicationContext.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("spring.main.web-application-type")).thenReturn("none");
    }

    private ProductionOpsProvisioningProperties createValidProperties() {
        ProductionOpsProvisioningProperties props = new ProductionOpsProvisioningProperties();
        props.setEnabled(true);
        props.setEmail("prod.ops.admin@ledgerguard.example.com");
        props.setFullName("Production Ops Admin");
        props.setPassword(UUID.randomUUID().toString() + "-SECURE");
        props.setExitOnCompletion(false);
        return props;
    }

    @Test
    @DisplayName("Provisions new OPS account when valid configuration provided and user does not exist")
    void provisionsNewOpsUser() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        when(userRepository.findByEmail(props.getEmail())).thenReturn(Optional.empty());

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        job.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());

        User savedUser = captor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo(props.getEmail());
        assertThat(savedUser.getRole()).isEqualTo(UserRole.OPS);
        assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(savedUser.getPasswordHash()).startsWith("$2a$");
        assertThat(passwordEncoder.matches(props.getPassword(), savedUser.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Idempotent when active OPS user already exists with matching email")
    void idempotentWhenOpsUserExists() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        User existingOps = new User(UUID.randomUUID(), props.getEmail(), "$2a$10$existingHash", UserRole.OPS, UserStatus.ACTIVE);
        when(userRepository.findByEmail(props.getEmail())).thenReturn(Optional.of(existingOps));

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        job.run(new DefaultApplicationArguments());

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Refuses promotion of existing CUSTOMER user to OPS role")
    void refusesPromotionOfCustomer() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        User existingCustomer = new User(UUID.randomUUID(), props.getEmail(), "$2a$10$existingHash", UserRole.CUSTOMER, UserStatus.ACTIVE);
        when(userRepository.findByEmail(props.getEmail())).thenReturn(Optional.of(existingCustomer));

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot promote existing non-OPS user to OPS role");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Refuses promotion of existing MERCHANT user to OPS role")
    void refusesPromotionOfMerchant() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        User existingMerchant = new User(UUID.randomUUID(), props.getEmail(), "$2a$10$existingHash", UserRole.MERCHANT, UserStatus.ACTIVE);
        when(userRepository.findByEmail(props.getEmail())).thenReturn(Optional.of(existingMerchant));

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot promote existing non-OPS user to OPS role");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Rejects short password below 12 characters")
    void rejectsShortPassword() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        props.setPassword("x".repeat(11));

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password must be at least 12 characters");
    }

    @Test
    @DisplayName("Rejects password exceeding 72 UTF-8 bytes")
    void rejectsOverlongPassword() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        props.setPassword("a".repeat(73));

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password exceeds maximum 72 UTF-8 bytes");
    }

    @Test
    @DisplayName("Rejects blank password")
    void rejectsBlankPassword() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        props.setPassword("   ");

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password must not be blank");
    }

    @Test
    @DisplayName("Rejects invalid email format")
    void rejectsInvalidEmail() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        props.setEmail("not-an-email");

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("email format is invalid");
    }

    @Test
    @DisplayName("Rejects disabled existing OPS account")
    void rejectsDisabledOpsAccount() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        User disabledOps = new User(UUID.randomUUID(), props.getEmail(), "$2a$10$existingHash", UserRole.OPS, UserStatus.DISABLED);
        when(userRepository.findByEmail(props.getEmail())).thenReturn(Optional.of(disabledOps));

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing OPS account is DISABLED");
    }

    @Test
    @DisplayName("Rejects blank or out-of-range full name")
    void rejectsInvalidFullName() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        props.setFullName("   ");

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full name must not be blank");

        props.setFullName("A");
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full name must be between 2 and 120 characters");

        props.setFullName("A".repeat(121));
        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full name must be between 2 and 120 characters");
    }

    @Test
    @DisplayName("Rotates password for existing active OPS account when rotatePassword is true")
    void rotatesPasswordWhenExplicitlyRequested() {
        ProductionOpsProvisioningProperties props = createValidProperties();
        props.setRotatePassword(true);
        props.setPassword("BrandNewRotatedPass123!");
        props.setFullName("Rotated Admin Name");

        User existingOps = new User(UUID.randomUUID(), "Old Admin Name", props.getEmail(), "$2a$10$oldHash", UserRole.OPS, UserStatus.ACTIVE);
        when(userRepository.findByEmail(props.getEmail())).thenReturn(Optional.of(existingOps));

        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);
        job.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());

        User updatedUser = captor.getValue();
        assertThat(updatedUser.getEmail()).isEqualTo(props.getEmail());
        assertThat(updatedUser.getFullName()).isEqualTo("Rotated Admin Name");
        assertThat(passwordEncoder.matches("BrandNewRotatedPass123!", updatedUser.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Fails fast when executed in a WebApplicationContext")
    void failsFastInWebApplicationContext() {
        WebApplicationContext webContext = mock(WebApplicationContext.class);
        when(webContext.getEnvironment()).thenReturn(environment);

        ProductionOpsProvisioningProperties props = createValidProperties();
        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, webContext);

        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot execute in web-server mode");
    }

    @Test
    @DisplayName("Fails fast when spring.main.web-application-type is servlet")
    void failsFastWhenWebAppTypeIsServlet() {
        when(environment.getProperty("spring.main.web-application-type")).thenReturn("servlet");

        ProductionOpsProvisioningProperties props = createValidProperties();
        ProductionOpsProvisioningJob job = new ProductionOpsProvisioningJob(props, userRepository, passwordEncoder, applicationContext);

        assertThatThrownBy(() -> job.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.main.web-application-type must be 'none'");
    }

    @Test
    @DisplayName("Disabled by default: ProductionOpsProvisioningJob bean is absent from ApplicationContext")
    void beanAbsentByDefault() {
        new ApplicationContextRunner()
                .withBean(UserRepository.class, () -> userRepository)
                .withBean(PasswordEncoder.class, () -> passwordEncoder)
                .withUserConfiguration(ProductionOpsProvisioningProperties.class, ProductionOpsProvisioningJob.class)
                .run(context -> assertThat(context).doesNotHaveBean(ProductionOpsProvisioningJob.class));
    }

    @Test
    @DisplayName("Absent when only ops-provision profile is active without property")
    void beanAbsentWithProfileOnly() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=ops-provision")
                .withBean(UserRepository.class, () -> userRepository)
                .withBean(PasswordEncoder.class, () -> passwordEncoder)
                .withUserConfiguration(ProductionOpsProvisioningProperties.class, ProductionOpsProvisioningJob.class)
                .run(context -> assertThat(context).doesNotHaveBean(ProductionOpsProvisioningJob.class));
    }

    @Test
    @DisplayName("Absent when only property is enabled without ops-provision profile")
    void beanAbsentWithPropertyOnly() {
        new ApplicationContextRunner()
                .withPropertyValues("ledgerguard.ops.provision.enabled=true")
                .withBean(UserRepository.class, () -> userRepository)
                .withBean(PasswordEncoder.class, () -> passwordEncoder)
                .withUserConfiguration(ProductionOpsProvisioningProperties.class, ProductionOpsProvisioningJob.class)
                .run(context -> assertThat(context).doesNotHaveBean(ProductionOpsProvisioningJob.class));
    }

    @Test
    @DisplayName("Present when both ops-provision profile and property are explicitly active")
    void beanPresentWhenBothProfileAndPropertyActive() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=ops-provision",
                        "ledgerguard.ops.provision.enabled=true"
                )
                .withBean(UserRepository.class, () -> userRepository)
                .withBean(PasswordEncoder.class, () -> passwordEncoder)
                .withUserConfiguration(ProductionOpsProvisioningProperties.class, ProductionOpsProvisioningService.class, ProductionOpsProvisioningJob.class)
                .run(context -> assertThat(context).hasSingleBean(ProductionOpsProvisioningJob.class));
    }
}
