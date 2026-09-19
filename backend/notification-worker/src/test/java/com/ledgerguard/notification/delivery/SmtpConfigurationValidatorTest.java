package com.ledgerguard.notification.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpConfigurationValidatorTest {

    @Test
    void shouldPassForValidLocalMailpitConfig() {
        SmtpConfigurationValidator validator = new SmtpConfigurationValidator(
                true,
                "localhost",
                1025,
                false,
                false,
                false,
                "",
                "",
                "no-reply@ledgerguard.local",
                "LedgerGuard",
                ""
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldPassForValidAuthenticatedSmtpConfig() {
        SmtpConfigurationValidator validator = new SmtpConfigurationValidator(
                true,
                "smtp.example.com",
                587,
                true,
                true,
                true,
                "smtp-user@example.com",
                "secret-password",
                "verified@domain.com",
                "LedgerGuard Production",
                "support@domain.com"
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldSkipValidationWhenEmailIsDisabled() {
        SmtpConfigurationValidator validator = new SmtpConfigurationValidator(
                false,
                "",
                0,
                true,
                false,
                false,
                "",
                "",
                "",
                "",
                ""
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldFailWhenHostIsBlank() {
        SmtpConfigurationValidator validator = new SmtpConfigurationValidator(
                true,
                "  ",
                587,
                false,
                false,
                false,
                "",
                "",
                "no-reply@ledgerguard.local",
                "LedgerGuard",
                ""
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("Host must not be blank"));
    }

    @Test
    void shouldFailWhenPortIsInvalid() {
        SmtpConfigurationValidator validatorLow = new SmtpConfigurationValidator(
                true,
                "localhost",
                0,
                false,
                false,
                false,
                "",
                "",
                "no-reply@ledgerguard.local",
                "LedgerGuard",
                ""
        );

        IllegalStateException ex1 = assertThrows(IllegalStateException.class, validatorLow::validate);
        assertTrue(ex1.getMessage().contains("Port must be between 1 and 65535"));

        SmtpConfigurationValidator validatorHigh = new SmtpConfigurationValidator(
                true,
                "localhost",
                70000,
                false,
                false,
                false,
                "",
                "",
                "no-reply@ledgerguard.local",
                "LedgerGuard",
                ""
        );

        IllegalStateException ex2 = assertThrows(IllegalStateException.class, validatorHigh::validate);
        assertTrue(ex2.getMessage().contains("Port must be between 1 and 65535"));
    }

    @Test
    void shouldFailWhenFromIsBlank() {
        SmtpConfigurationValidator validator = new SmtpConfigurationValidator(
                true,
                "localhost",
                1025,
                false,
                false,
                false,
                "",
                "",
                "  ",
                "LedgerGuard",
                ""
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("From address must not be blank"));
    }

    @Test
    void shouldFailWhenAuthEnabledButUsernameIsMissing() {
        SmtpConfigurationValidator validator = new SmtpConfigurationValidator(
                true,
                "smtp.example.com",
                587,
                true,
                true,
                false,
                "",
                "some-password",
                "no-reply@ledgerguard.local",
                "LedgerGuard",
                ""
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("Username must not be blank"));
    }

    @Test
    void shouldFailWhenAuthEnabledButPasswordIsMissing() {
        SmtpConfigurationValidator validator = new SmtpConfigurationValidator(
                true,
                "smtp.example.com",
                587,
                true,
                true,
                false,
                "my-user",
                "  ",
                "no-reply@ledgerguard.local",
                "LedgerGuard",
                ""
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("Password must not be blank"));
    }
}
