package com.ledgerguard.identity;

import com.ledgerguard.identity.infrastructure.PasswordRecoveryConfigurationValidator;
import com.ledgerguard.identity.infrastructure.PasswordRecoveryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PasswordRecoveryConfigurationValidator Tests")
class PasswordRecoveryConfigurationValidatorTest {

    private PasswordRecoveryProperties createProperties(String frontendUrl, String fromEmail) {
        PasswordRecoveryProperties props = new PasswordRecoveryProperties();
        props.setFrontendBaseUrl(frontendUrl);
        props.setFromEmail(fromEmail);
        return props;
    }

    private PasswordRecoveryConfigurationValidator createValidator(PasswordRecoveryProperties props, MockEnvironment env) {
        PasswordRecoveryConfigurationValidator validator = new PasswordRecoveryConfigurationValidator(props, env);
        ReflectionTestUtils.setField(validator, "mailHost", "localhost");
        ReflectionTestUtils.setField(validator, "mailPort", 1025);
        ReflectionTestUtils.setField(validator, "smtpAuth", false);
        ReflectionTestUtils.setField(validator, "starttlsEnable", false);
        ReflectionTestUtils.setField(validator, "connectionTimeoutMs", 10000);
        ReflectionTestUtils.setField(validator, "readTimeoutMs", 10000);
        ReflectionTestUtils.setField(validator, "writeTimeoutMs", 10000);
        return validator;
    }

    @Test
    @DisplayName("Development environment permits localhost HTTP frontend URL")
    void devPermitsLocalhostHttp() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        PasswordRecoveryProperties props = createProperties("http://localhost:5173", "no-reply@ledgerguard.local");
        PasswordRecoveryConfigurationValidator validator = createValidator(props, env);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Development environment rejects non-localhost plain HTTP frontend URL")
    void devRejectsNonLocalhostHttp() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        PasswordRecoveryProperties props = createProperties("http://insecure.example.com", "no-reply@ledgerguard.local");
        PasswordRecoveryConfigurationValidator validator = createValidator(props, env);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plain HTTP is only permitted for localhost development");
    }

    @Test
    @DisplayName("Production environment rejects plain HTTP frontend URL")
    void prodRejectsHttp() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        PasswordRecoveryProperties props = createProperties("http://localhost:5173", "no-reply@ledgerguard.local");
        PasswordRecoveryConfigurationValidator validator = createValidator(props, env);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("production environment requires HTTPS frontendBaseUrl");
    }

    @Test
    @DisplayName("Production environment accepts valid HTTPS frontend URL")
    void prodAcceptsHttps() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        PasswordRecoveryProperties props = createProperties("https://ledgerguard.example.com", "security@ledgerguard.com");
        PasswordRecoveryConfigurationValidator validator = createValidator(props, env);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Rejects invalid sender email format")
    void rejectsInvalidSenderEmail() {
        MockEnvironment env = new MockEnvironment();
        PasswordRecoveryProperties props = createProperties("http://localhost:5173", "not-an-email");
        PasswordRecoveryConfigurationValidator validator = createValidator(props, env);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fromEmail must be a valid email address");
    }

    @Test
    @DisplayName("Rejects non-positive or out of range SMTP timeouts")
    void rejectsOutOfRangeTimeouts() {
        MockEnvironment env = new MockEnvironment();
        PasswordRecoveryProperties props = createProperties("http://localhost:5173", "no-reply@ledgerguard.local");
        PasswordRecoveryConfigurationValidator validator = createValidator(props, env);
        ReflectionTestUtils.setField(validator, "connectionTimeoutMs", 0);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mail connection timeout must be between 1ms and 60000ms");
    }
}
