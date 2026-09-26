package com.ledgerguard.identity;

import com.ledgerguard.identity.domain.UserRepository;
import com.ledgerguard.identity.infrastructure.DevelopmentOpsBootstrap;
import com.ledgerguard.identity.infrastructure.OpsBootstrapProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DevelopmentOpsBootstrapProfileActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(Environment.class, () -> mock(Environment.class))
            .withUserConfiguration(OpsBootstrapProperties.class, DevelopmentOpsBootstrap.class);

    @Test
    @DisplayName("No active profile: DevelopmentOpsBootstrap bean is absent")
    void beanAbsentWhenNoProfile() {
        contextRunner.withPropertyValues("ledgerguard.bootstrap.ops.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentOpsBootstrap.class));
    }

    @Test
    @DisplayName("dev profile + disabled: DevelopmentOpsBootstrap bean is absent")
    void beanAbsentWhenDevProfileAndDisabled() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "ledgerguard.bootstrap.ops.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentOpsBootstrap.class));
    }

    @Test
    @DisplayName("dev profile + enabled: DevelopmentOpsBootstrap bean is present")
    void beanPresentWhenDevProfileAndEnabled() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "ledgerguard.bootstrap.ops.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DevelopmentOpsBootstrap.class));
    }

    @Test
    @DisplayName("test profile: DevelopmentOpsBootstrap bean is absent")
    void beanAbsentWhenTestProfile() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=test",
                        "ledgerguard.bootstrap.ops.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentOpsBootstrap.class));
    }

    @Test
    @DisplayName("staging profile: DevelopmentOpsBootstrap bean is absent")
    void beanAbsentWhenStagingProfile() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=staging",
                        "ledgerguard.bootstrap.ops.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentOpsBootstrap.class));
    }

    @Test
    @DisplayName("prod profile: DevelopmentOpsBootstrap bean is absent")
    void beanAbsentWhenProdProfile() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "ledgerguard.bootstrap.ops.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentOpsBootstrap.class));
    }

    @Test
    @DisplayName("production profile: DevelopmentOpsBootstrap bean is absent")
    void beanAbsentWhenProductionProfile() {
        contextRunner.withPropertyValues(
                        "spring.profiles.active=production",
                        "ledgerguard.bootstrap.ops.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevelopmentOpsBootstrap.class));
    }
}
