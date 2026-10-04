package com.ledgerguard.identity.application;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicyValidator {

    public static final int MIN_PASSWORD_CHAR_LENGTH = 12;
    public static final int MAX_PASSWORD_UTF8_BYTES = 72;

    private PasswordPolicyValidator() {}

    public static void validate(String password) {
        if (password == null || password.length() < MIN_PASSWORD_CHAR_LENGTH) {
            throw new InvalidPasswordException("Password must be at least " + MIN_PASSWORD_CHAR_LENGTH + " characters.");
        }
        byte[] utf8Bytes = password.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > MAX_PASSWORD_UTF8_BYTES) {
            throw new InvalidPasswordException("Password exceeds maximum allowed BCrypt byte length of " + MAX_PASSWORD_UTF8_BYTES + " UTF-8 bytes.");
        }
    }
}
